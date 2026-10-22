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
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.RefreshSaleStatus
import com.handpoint.safclient.domain.usecase.SendStoredSales
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    /** Total of the sales waiting to be sent; `null` when there are none. */
    val pending: Money? = null,
    val pendingCount: Int = 0,
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
    val canSend: Boolean get() = !isSending && pendingCount > 0
}

/**
 * Home: what is waiting to be sent, and today's sales with their status.
 *
 * When the screen opens it connects the terminal (unless it is connected already), makes the
 * journal agree with the terminal, and then asks the gateway once about today's sales whose
 * outcome is still open. Each time the screen comes back to the front it reconciles again, at
 * most every [RECONCILE_INTERVAL].
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    journal: SalesJournal,
    private val terminal: StoreAndForwardTerminal,
    settings: AppPreferences,
    private val reconcile: ReconcileStoredSales,
    private val refresh: RefreshSaleStatus,
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
    ) { sales, merchantName, connection, isSending ->
        val stored = sales.filter { it.status == ProcessingStatus.STORED }.sortedByDescending { it.approvedAt }
        HomeUiState(
            merchantName = merchantName,
            connection = connection,
            pending = stored.totalOrNull(),
            pendingCount = stored.size,
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

    private var connecting: Job = connectInBackground()

    init {
        viewModelScope.launch {
            connecting.join()
            reconcileNow()
            // The throttle counts from here, so the resume that follows the opening is skipped.
            lastReconcileAt = clock.instant()
            refreshOpenStatuses(journal)
        }
    }

    /** Call when the screen comes back to the front. */
    fun onResume() {
        val last = lastReconcileAt ?: return // the opening reconcile is still running
        val now = clock.instant()
        if (Duration.between(last, now) < RECONCILE_INTERVAL) return
        lastReconcileAt = now
        viewModelScope.launch {
            connecting.join()
            reconcileNow()
        }
    }

    /** Tries to connect the terminal again (the merchant tapped the connection chip). */
    fun retryConnection() {
        if (connecting.isActive || terminal.connection.value == TerminalConnection.CONNECTED) return
        connecting = connectInBackground()
        viewModelScope.launch {
            connecting.join()
            reconcileNow()
        }
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

    /** Asks the gateway about today's sales that were sent but have no final answer yet. */
    private suspend fun refreshOpenStatuses(journal: SalesJournal) {
        journal.observeAll().first()
            .filter { it.status in OPEN_STATUSES && it.approvedAt.isToday() }
            .forEach { sale -> errors.reportingErrors("refresh status") { withContext(io) { refresh(sale.reference) } } }
    }

    private fun Instant.isToday(): Boolean = atZone(clock.zone).toLocalDate() == LocalDate.now(clock)

    companion object {
        /** Shortest time between two reconciles caused by the screen coming back. */
        val RECONCILE_INTERVAL: Duration = Duration.ofSeconds(30)

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private val OPEN_STATUSES = setOf(ProcessingStatus.IN_PROGRESS, ProcessingStatus.NOT_FOUND)
    }
}

/**
 * Sum of the sales' amounts. `null` when there are none, or when they are in more than one
 * currency (stored sales share the terminal's offline currency, so that is not expected).
 */
private fun List<Sale>.totalOrNull(): Money? {
    if (isEmpty() || distinctBy { it.amount.currency }.size > 1) return null
    return map { it.amount }.reduce(Money::plus)
}
