package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes the journal agree with the terminal. The terminal is the authority for what is still
 * stored: the journal never decides on its own that a sale left the terminal, or that a sale
 * is not on it. Only this use case moves a sale into or out of STORED on that ground.
 *
 * - A sale the journal holds as STORED but the terminal does not list has left the terminal
 *   (it was forwarded, for example by a run the app did not see end), so it becomes IN_PROGRESS.
 * - A sale the journal holds as IN_PROGRESS or NOT_FOUND but the terminal still lists goes
 *   back to STORED.
 *
 * The terminal's list is a snapshot. A sale recorded, sent or checked after the snapshot
 * started is left as it is, so a sale taken (or forwarded) while the terminal answers is not
 * moved by a list that could not include it.
 *
 * Use this on app start, after a forwarding run, and after a sale with an unknown outcome.
 * [lastSummary] keeps the last answer, so the app can show sales the terminal holds but the
 * journal does not (for example a sale stored without its result reaching the app).
 *
 * The first call may create the SDK: call it off the main thread.
 */
@Singleton
class ReconcileStoredSales @Inject constructor(
    private val terminal: StoreAndForwardTerminal,
    private val journal: SalesJournal,
    private val settings: AppPreferences,
    private val clock: Clock,
) {
    private val summary = MutableStateFlow<StoredSummary?>(null)

    /** The terminal's last answer; `null` until the terminal has answered once. */
    val lastSummary: StateFlow<StoredSummary?> = summary.asStateFlow()

    /**
     * Returns what the terminal still stores, or `null` when it does not answer (then nothing
     * changes and [lastSummary] keeps the previous answer).
     */
    suspend operator fun invoke(): StoredSummary? {
        // Until the SDK has finished its set-up, its answer carries the built-in default
        // currency (GBP), not the terminal's configuration: ask only once it is connected.
        if (terminal.connection.value != TerminalConnection.CONNECTED) return null
        val snapshotStart = clock.instant()
        val stored = terminal.storedOnTerminal() ?: return null
        val onTerminal = stored.references.toSet()

        journal.pending()
            .filter { it.reference !in onTerminal && it.approvedAt.isBefore(snapshotStart) }
            .forEach { journal.markSent(it.reference, clock.instant()) }

        onTerminal.forEach { reference ->
            val sale = journal.find(reference) ?: return@forEach
            if (sale.status in BACK_TO_STORED && sale.lastChange().isBefore(snapshotStart)) {
                journal.markStored(reference)
            }
        }

        stored.total?.let { settings.setSafCurrency(it.currency) }
        summary.value = stored
        return stored
    }

    /**
     * Drops a sale from [lastSummary] once it is known to be off the terminal (deleted, or
     * the terminal said it does not hold it), so the app does not wait for the next answer.
     * [amount] is taken off the total when the currencies match.
     */
    fun leftTerminal(reference: SaleReference, amount: Money?) = summary.update { current ->
        if (current == null || reference !in current.references) return@update current
        val total = current.total?.let { total ->
            if (amount != null && amount.currency == total.currency) total - amount else total
        }
        StoredSummary(current.references - reference, total?.takeIf { current.references.size > 1 })
    }

    private fun Sale.lastChange(): Instant = listOfNotNull(approvedAt, sentAt, statusCheckedAt).max()

    private companion object {
        val BACK_TO_STORED = setOf(ProcessingStatus.IN_PROGRESS, ProcessingStatus.NOT_FOUND)
    }
}
