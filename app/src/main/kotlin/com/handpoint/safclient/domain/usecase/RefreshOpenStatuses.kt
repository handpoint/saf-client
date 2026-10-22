package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.ApplicationScope
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.SaleReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Asks Handpoint for the result of sales that were forwarded but have no final status yet,
 * and keeps the load on Handpoint low:
 *
 * - It asks only about open offline sales (IN_PROGRESS or NOT_FOUND) sent in the last [WINDOW].
 *   It never asks again about a sale with a final status, nor about an online sale (they are
 *   not Store and Forward sales; an UNKNOWN one is checked by the merchant with Handpoint).
 * - It asks about one sale at a time, newest first, and at most [MAX_PER_PASS] per pass.
 * - A pass stops at the first sale with no answer: without network, the rest would fail too.
 * - After a forwarding run, [followUp] asks about the sales just sent at each time in
 *   [FOLLOW_UP_AT], then stops. Handpoint processes forwarded sales asynchronously, so the
 *   first answer is often IN_PROGRESS.
 *
 * Handpoint has no batch status query: each sale is one `getTransactionProcessingStatus` call.
 * While a sale is still queued, Handpoint answers IN_PROGRESS without asking the gateway.
 *
 * Only the Store and Forward API answers these questions, so [isActive] tells the app not to
 * switch to the online API meanwhile.
 */
@Singleton
class RefreshOpenStatuses @Inject constructor(
    private val journal: SalesJournal,
    private val refresh: RefreshSaleStatus,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val passes = Mutex()
    private var followUp: Job? = null

    /** Passes and follow-ups in progress; [isActive] while above zero. Guarded by [active]. */
    private var activity = 0
    private val active = MutableStateFlow(false)

    /**
     * `true` while a pass runs or a follow-up is scheduled: from the [followUp] call (not later)
     * until its last question is answered.
     */
    val isActive: StateFlow<Boolean> = active.asStateFlow()

    /**
     * Runs one pass over the open sales. Skipped when another pass is running.
     * Returns how many sales it asked about.
     */
    suspend operator fun invoke(): Int {
        if (!passes.tryLock()) return 0
        begin()
        try {
            return ask(openSales())
        } finally {
            end()
            passes.unlock()
        }
    }

    /**
     * Asks about [references] (sales just forwarded) at each time in [FOLLOW_UP_AT], until all
     * of them have a final status. Runs in the app scope and replaces an earlier follow-up.
     */
    fun followUp(references: Collection<SaleReference>) {
        if (references.isEmpty()) return
        val sent = references.toList()
        followUp?.cancel()
        begin()
        followUp = appScope.launch {
            var waited = kotlin.time.Duration.ZERO
            for (at in FOLLOW_UP_AT) {
                delay(at - waited)
                waited = at
                val open = sent.mapNotNull { journal.find(it) }.filter { it.isOpen() }
                if (open.isEmpty()) return@launch
                passes.withLock { ask(open.take(MAX_PER_PASS)) }
            }
        }.apply {
            // Runs also when the follow-up is replaced, even before it started.
            invokeOnCompletion { end() }
        }
    }

    private fun begin() = synchronized(active) {
        activity++
        active.value = true
    }

    private fun end() = synchronized(active) {
        activity--
        active.value = activity > 0
    }

    private suspend fun openSales(): List<Sale> {
        val now = clock.instant()
        return journal.observeAll().first()
            .filter { it.isOpen() && Duration.between(it.sentAt ?: it.approvedAt, now) <= WINDOW }
            .sortedByDescending { it.sentAt ?: it.approvedAt }
            .take(MAX_PER_PASS)
    }

    private fun Sale.isOpen(): Boolean = channel == SaleChannel.OFFLINE && status in OPEN

    private suspend fun ask(sales: List<Sale>): Int {
        var asked = 0
        for (sale in sales) {
            asked++
            refresh(sale.reference) ?: break
        }
        return asked
    }

    companion object {
        /** Times after a forwarding run at which [followUp] asks about the sales just sent. */
        val FOLLOW_UP_AT = listOf(10.seconds, 30.seconds, 2.minutes)

        /** Most sales asked about in one pass. */
        const val MAX_PER_PASS = 20

        /** Sales sent longer ago than this are no longer asked about automatically. */
        val WINDOW: Duration = Duration.ofHours(24)

        private val OPEN = setOf(ProcessingStatus.IN_PROGRESS, ProcessingStatus.NOT_FOUND)
    }
}
