package com.handpoint.safclient.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handpoint.safclient.di.IoDispatcher
import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.reportingErrors
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.RefreshOpenStatuses
import com.handpoint.safclient.domain.usecase.SendStoredSales
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** What the Home screen shows. */
data class HomeUiState(
    val merchantName: String = "",
    val connection: TerminalConnection = TerminalConnection.DISCONNECTED,
    /**
     * Total of the sales waiting to be sent; `null` when there are none. Like [pendingCount],
     * it comes from the terminal's last answer when that is known (see [HomeViewModel]).
     */
    val pending: Money? = null,
    val pendingCount: Int = 0,
    /** Sales the terminal stores that are not in [stored] (the journal never got them). */
    val notInList: Int = 0,
    /** Sales approved today (local date), newest first. */
    val today: List<Sale> = emptyList(),
    /** Every sale waiting to be sent, newest first, whatever the day. */
    val stored: List<Sale> = emptyList(),
    /** `true` while a forwarding run is active. */
    val isSending: Boolean = false,
    /** `true` until the journal has been read once. */
    val isLoading: Boolean = true,
) {
    val canTakeSale: Boolean get() = connection == TerminalConnection.CONNECTED
    val canSend: Boolean get() = !isSending && pendingCount > 0 && connection == TerminalConnection.CONNECTED
}

/**
 * Home: what is waiting to be sent, and today's sales with their status.
 *
 * The terminal is the authority for what is waiting to be sent. The hero card counts the
 * terminal's last answer ([ReconcileStoredSales.lastSummary]) when it is known, so a sale the
 * terminal stored without reporting it still shows and can be sent. The journal counts when it
 * holds more (a sale taken after that answer), unless its total is in another currency.
 *
 * When the screen opens it connects the terminal (unless it is connected already), makes the
 * journal agree with the terminal, and then asks Handpoint about the sales whose outcome is
 * still open ([RefreshOpenStatuses]). Each time the screen comes back to the front it
 * reconciles again, at most every [RECONCILE_INTERVAL], and asks about open sales again, at
 * most every [STATUS_INTERVAL].
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    journal: SalesJournal,
    private val terminal: StoreAndForwardTerminal,
    settings: AppPreferences,
    private val reconcile: ReconcileStoredSales,
    private val statuses: RefreshOpenStatuses,
    sendStoredSales: SendStoredSales,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        journal.observeAll(),
        settings.merchantName,
        terminal.connection,
        sendStoredSales.isRunning,
        reconcile.lastSummary,
    ) { sales, merchantName, connection, isSending, onTerminal ->
        val stored = sales.filter { it.status == ProcessingStatus.STORED }.sortedByDescending { it.approvedAt }
        val waiting = waitingToSend(stored, onTerminal)
        HomeUiState(
            merchantName = merchantName,
            connection = connection,
            pending = waiting.total,
            pendingCount = waiting.count,
            notInList = onTerminal?.let { summary ->
                val listed = stored.map { it.reference }.toSet()
                summary.references.count { it !in listed }
            } ?: 0,
            today = sales.filter { it.approvedAt.isToday() }.sortedByDescending { it.approvedAt },
            stored = stored,
            isSending = isSending,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HomeUiState(connection = terminal.connection.value),
    )

    /** End of the last reconcile Home started; `null` until the opening reconcile is done. */
    private var lastReconcileAt: Instant? = null

    /** Start of the last status pass Home started; `null` until the opening pass. */
    private var lastStatusPassAt: Instant? = null

    private var connecting: Job = connectInBackground()

    init {
        // connect() returns before the SDK has finished its set-up, and the terminal's answer
        // is only right once it is connected. So reconcile each time the terminal becomes
        // CONNECTED: at opening, and again after it reconnects.
        viewModelScope.launch {
            connecting.join()
            var opening = true
            terminal.connection.filter { it == TerminalConnection.CONNECTED }.collect {
                reconcileNow()
                if (opening) {
                    opening = false
                    // The throttle counts from here, so the resume that follows the opening is skipped.
                    lastReconcileAt = clock.instant()
                    lastStatusPassAt = clock.instant()
                    refreshOpenStatuses()
                }
            }
        }
    }

    /** Call when the screen comes back to the front. */
    fun onResume() {
        val now = clock.instant()
        // Both are null while the opening reconcile is still running.
        lastReconcileAt?.let { last ->
            if (Duration.between(last, now) >= RECONCILE_INTERVAL) {
                lastReconcileAt = now
                viewModelScope.launch {
                    connecting.join()
                    reconcileNow()
                }
            }
        }
        lastStatusPassAt?.let { last ->
            if (Duration.between(last, now) >= STATUS_INTERVAL) {
                lastStatusPassAt = now
                viewModelScope.launch { refreshOpenStatuses() }
            }
        }
    }

    /** Tries to connect the terminal again (the merchant tapped the connection chip). */
    fun retryConnection() {
        if (connecting.isActive || terminal.connection.value == TerminalConnection.CONNECTED) return
        connecting = connectInBackground()
    }

    private fun connectInBackground(): Job = viewModelScope.launch {
        if (terminal.connection.value != TerminalConnection.CONNECTED) {
            // The first connect() creates the SDK, which is heavy and can fail: keep it off the
            // main thread, and on failure leave the chip showing the terminal is not connected.
            errors.reportingErrors("connect") { withContext(io) { terminal.connect() } }
        }
    }

    // Connect, reconcile and refresh are best effort here: when they fail, the error is
    // reported, Home keeps showing what the journal holds, and the next resume (or a tap on
    // the connection chip) tries again.
    private suspend fun reconcileNow() {
        errors.reportingErrors("reconcile") { withContext(io) { reconcile() } }
    }

    /** Asks Handpoint about the sales that were sent but have no final answer yet. */
    private suspend fun refreshOpenStatuses() {
        errors.reportingErrors("refresh status") { withContext(io) { statuses() } }
    }

    private fun Instant.isToday(): Boolean = atZone(clock.zone).toLocalDate() == LocalDate.now(clock)

    companion object {
        /** Shortest time between two reconciles caused by the screen coming back. */
        val RECONCILE_INTERVAL: Duration = Duration.ofSeconds(30)

        /** Shortest time between two status passes caused by the screen coming back. */
        val STATUS_INTERVAL: Duration = Duration.ofMinutes(5)

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private class Waiting(val count: Int, val total: Money?)

/**
 * What is waiting to be sent: the terminal's answer when it is known, unless the journal holds
 * more sales in the same currency (sales taken after that answer). Totals in different
 * currencies mean the journal is out of date, so the terminal wins.
 */
private fun waitingToSend(stored: List<Sale>, onTerminal: StoredSummary?): Waiting {
    val journal = Waiting(stored.size, stored.totalOrNull())
    if (onTerminal == null) return journal
    val terminal = Waiting(onTerminal.references.size, onTerminal.total)
    val otherCurrency = journal.total != null && onTerminal.total != null &&
        journal.total.currency != onTerminal.total.currency
    return if (terminal.count >= journal.count || otherCurrency) terminal else journal
}

/**
 * Sum of the sales' amounts. `null` when there are none, or when they are in more than one
 * currency (stored sales share the terminal's offline currency, so that is not expected).
 */
private fun List<Sale>.totalOrNull(): Money? {
    if (isEmpty() || distinctBy { it.amount.currency }.size > 1) return null
    return map { it.amount }.reduce(Money::plus)
}
