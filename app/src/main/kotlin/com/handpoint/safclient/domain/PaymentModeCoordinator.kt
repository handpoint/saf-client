package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.ModeState
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.OnlineWait
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.RefreshOpenStatuses
import com.handpoint.safclient.domain.usecase.SendStoredSales
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Chooses the payment API (online or Store and Forward) and switches between them, without
 * asking the merchant.
 *
 * ## The rule
 *
 * - **No network:** Store and Forward ([PaymentMode.OFFLINE]). Sales are stored on the terminal.
 * - **Network, and nothing waiting to be sent:** the online API ([PaymentMode.ONLINE]).
 * - **Network, and sales waiting to be sent:** stay on Store and Forward. Only that API can
 *   send stored sales and ask for their status, so the merchant can send them ("Send now").
 *   Once the run and its status follow-up have finished and nothing is waiting, switch to
 *   online.
 *
 * "Nothing waiting" needs the terminal's own answer ([ReconcileStoredSales.lastSummary]): the
 * terminal can hold sales the journal does not know. Until it has answered once, the app stays
 * on Store and Forward.
 *
 * ## When it switches
 *
 * Never during a sale ([beginSale] to [endSale]), a forwarding run ([SendStoredSales.isRunning])
 * or a status follow-up ([RefreshOpenStatuses.isActive]): a wanted switch waits until the app
 * is idle. While a switch runs ([ModeState.switchingTo]), no sale starts.
 *
 * A switch deactivates one API, activates the other and waits for it to be CONNECTED (about 5
 * to 10 seconds; see [PaymentTerminal]). If the online API is not connected after
 * [SWITCH_TIMEOUT], or cannot be created, the app goes back to Store and Forward, reports the
 * error, and tries online again later, waiting longer after each failure
 * ([RETRY_ONLINE_DELAYS]: 2, 5, 15, then every 30 minutes). A retry never starts while the
 * merchant is on the charge screen. When the network comes back after a drop, the wait starts
 * again from the shortest.
 *
 * After a switch to Store and Forward caused by a network drop, the network must stay up for
 * [STABLE_NETWORK_FOR] before the app goes online again, so a network that comes and goes does
 * not make the app switch back and forth.
 *
 * Call [start] once, at app start (`SafClientApp`); it runs in [appScope] for as long as the
 * app runs. Later calls do nothing.
 *
 * @param io runs the SDK calls, which block.
 */
class PaymentModeCoordinator(
    private val saf: StoreAndForwardTerminal,
    private val online: OnlineTerminal,
    private val network: NetworkMonitor,
    private val journal: SalesJournal,
    private val reconcile: ReconcileStoredSales,
    private val send: SendStoredSales,
    private val statuses: RefreshOpenStatuses,
    private val errors: ErrorReporter,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    /** Guards the decision to switch, the failure count and the jobs below. */
    private val lock = Any()

    private val started = AtomicBoolean(false)

    private val salesInProgress = MutableStateFlow(0)

    /** Charge screens open (amount entry, card, result): a retry to online waits for them to close. */
    private val chargeScreens = MutableStateFlow(0)

    /** What, besides the inputs, holds the app back from online. */
    private val control = MutableStateFlow(Control())

    /** Failed switches to online in a row; picks the next wait in [RETRY_ONLINE_DELAYS]. */
    private var failures = 0

    /** The one wait before the next online attempt. A new one replaces it. */
    private var retryJob: Job? = null

    /** Counts [STABLE_NETWORK_FOR] since the network came up. */
    private var stableJob: Job? = null

    private val _state = MutableStateFlow(
        ModeState(network = network.status.value, wasOffline = network.status.value == NetworkStatus.OFFLINE),
    )

    /** The payment mode, a switch in progress, and what the merchant needs to know about it. */
    val state: StateFlow<ModeState> = _state.asStateFlow()

    /** Connection of the API in use; CONNECTING while a switch runs. */
    val connection: StateFlow<TerminalConnection> =
        combine(_state, saf.connection, online.connection) { state, safConnection, onlineConnection ->
            when {
                state.isSwitching -> TerminalConnection.CONNECTING
                state.mode == PaymentMode.ONLINE -> onlineConnection
                else -> safConnection
            }
        }.stateIn(appScope, SharingStarted.Eagerly, saf.connection.value)

    /** Card prompts of the sale in progress, whichever API runs it. Only the active API sends any. */
    val cardSteps: Flow<CardStep> = merge(saf.cardSteps, online.cardSteps)

    /** Starts applying the rule. Later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch { watchNetwork() }
        val busy = combine(salesInProgress, send.isRunning, statuses.isActive, chargeScreens) { _, _, _, _ -> Unit }
        appScope.launch {
            combine(
                network.status,
                journal.observeAll().map { sales -> sales.count { it.status == ProcessingStatus.STORED } }
                    .distinctUntilChanged(),
                reconcile.lastSummary,
                busy,
                control,
            ) { status, stored, summary, _, _ -> Inputs(status, stored, summary) }
                // A switch suspends this collector; meanwhile only the latest inputs matter.
                .conflate()
                .collect { inputs ->
                    _state.update {
                        it.copy(network = inputs.network, waitingToSend = inputs.waitingToSend())
                    }
                    evaluate(inputs)
                }
        }
    }

    /**
     * Reserves the API in use for a sale and returns its mode, or `null` while a switch runs
     * (then do not start the sale). Call [endSale] when the sale has ended, whatever happened.
     */
    fun beginSale(): PaymentMode? = synchronized(lock) {
        val state = _state.value
        if (state.isSwitching) return null
        salesInProgress.update { it + 1 }
        state.mode
    }

    /** Ends the sale [beginSale] started. A switch that waited for it may run now. */
    fun endSale() {
        salesInProgress.update { (it - 1).coerceAtLeast(0) }
    }

    /** The merchant opened the charge screen: a retry to online waits until [leaveChargeScreen]. */
    fun enterChargeScreen() {
        chargeScreens.update { it + 1 }
    }

    fun leaveChargeScreen() {
        chargeScreens.update { (it - 1).coerceAtLeast(0) }
    }

    /** Connects the API in use again. Ignored (`false`) while a switch runs. Blocks: call off the main thread. */
    fun connect(): Boolean {
        val state = _state.value
        if (state.isSwitching) return false
        return terminal(state.mode).connect()
    }

    /** Disconnects the API in use. Blocks: call off the main thread. */
    fun disconnect(): Boolean = terminal(_state.value.mode).disconnect()

    /** Asks the API in use to stop the current sale. */
    fun cancelSale(): Boolean = terminal(_state.value.mode).cancelSale()

    /** Prints on the terminal printer through the API in use. */
    fun printReceipt(receipt: String): Boolean = terminal(_state.value.mode).printReceipt(receipt)

    /**
     * Follows the network: a return after a drop restarts the online retries from the shortest
     * wait and starts the stability count; a drop is remembered so the banner can say "back
     * online". When the network is up and the terminal has never said what it stores, it is
     * asked now, so the app does not wait for it forever.
     */
    private suspend fun watchNetwork() {
        var previous: NetworkStatus? = null
        network.status.collect { status ->
            when (status) {
                NetworkStatus.ONLINE -> {
                    if (previous == NetworkStatus.OFFLINE) resetRetries()
                    if (previous != NetworkStatus.ONLINE) startStabilityCount()
                    if (reconcile.lastSummary.value == null) {
                        appScope.launch { errors.reportingErrors("reconcile") { withContext(io) { reconcile() } } }
                    }
                }
                NetworkStatus.OFFLINE -> {
                    synchronized(lock) { stableJob?.cancel() }
                    control.update { it.copy(networkStable = false) }
                    _state.update { it.copy(wasOffline = true) }
                }
            }
            previous = status
        }
    }

    private fun resetRetries() = synchronized(lock) {
        failures = 0
        retryJob?.cancel()
        retryJob = null
        control.update { it.copy(onlineBlocked = false) }
    }

    private fun startStabilityCount() = synchronized(lock) {
        stableJob?.cancel()
        control.update { it.copy(networkStable = false) }
        stableJob = appScope.launch {
            delay(STABLE_NETWORK_FOR)
            control.update { it.copy(networkStable = true) }
        }
    }

    private suspend fun evaluate(inputs: Inputs) {
        val target = synchronized(lock) {
            val state = _state.value
            if (state.isSwitching) return
            val wanted = wantedMode(inputs)
            val wait = if (wanted == PaymentMode.OFFLINE || isBusy()) onlineWait(inputs) else null
            if (wanted == state.mode || isBusy()) {
                _state.value = state.copy(onlineWait = wait)
                return
            }
            _state.value = state.copy(switchingTo = wanted, onlineWait = null)
            wanted
        }
        switchTo(target)
    }

    /** Called under [lock]. */
    private fun wantedMode(inputs: Inputs): PaymentMode {
        if (inputs.network == NetworkStatus.OFFLINE) return PaymentMode.OFFLINE
        if (!inputs.nothingWaiting()) return PaymentMode.OFFLINE
        val control = control.value
        val held = control.onlineBlocked ||
            (control.afterNetworkLoss && !control.networkStable) ||
            (failures > 0 && chargeScreens.value > 0)
        return if (held) PaymentMode.OFFLINE else PaymentMode.ONLINE
    }

    /**
     * Why the app stays on Store and Forward although the network is up and nothing waits:
     * `null` when online is in use, the network is down, sales wait, or a failed switch holds
     * it (the banner says so through [ModeState.onlineUnavailable]).
     */
    private fun onlineWait(inputs: Inputs): OnlineWait? {
        val control = control.value
        return when {
            _state.value.mode == PaymentMode.ONLINE || inputs.network == NetworkStatus.OFFLINE -> null
            inputs.summary == null && inputs.storedInJournal == 0 -> OnlineWait.AWAITING_TERMINAL
            !inputs.nothingWaiting() || control.onlineBlocked -> null
            control.afterNetworkLoss && !control.networkStable -> OnlineWait.NETWORK_SETTLING
            statuses.isActive.value -> OnlineWait.BUSY_FOLLOW_UP
            else -> OnlineWait.WAITING_IDLE
        }
    }

    /** Read live, not from the inputs: they may be a moment old. */
    private fun isBusy(): Boolean =
        salesInProgress.value > 0 || send.isRunning.value || statuses.isActive.value

    private suspend fun switchTo(target: PaymentMode) {
        val error = connectTo(target)
        if (error == null) {
            _state.update {
                it.copy(
                    mode = target,
                    switchingTo = null,
                    onlineUnavailable = if (target == PaymentMode.ONLINE) false else it.onlineUnavailable,
                )
            }
            if (target == PaymentMode.ONLINE) {
                synchronized(lock) { failures = 0 }
                control.update { it.copy(afterNetworkLoss = false) }
            } else if (network.status.value == NetworkStatus.OFFLINE) {
                // The network dropped: wait for it to be stable before going online again.
                control.update { it.copy(afterNetworkLoss = true) }
            }
            return
        }
        errors.report("switch to ${target.name.lowercase()}", error)
        if (target == PaymentMode.ONLINE) {
            fallBackToStoreAndForward()
        } else {
            // Store and Forward is the fallback already: stay on it. Its connection shows the
            // problem, and the merchant can retry the connection.
            _state.update { it.copy(mode = PaymentMode.OFFLINE, switchingTo = null) }
        }
    }

    /**
     * Deactivates the other API, activates the one for [target] and waits for it to be
     * CONNECTED. Returns `null` on success, else what went wrong.
     */
    private suspend fun connectTo(target: PaymentMode): Exception? {
        val from = terminal(target.other())
        val to = terminal(target)
        return try {
            val connected = withTimeoutOrNull(SWITCH_TIMEOUT) {
                withContext(io) {
                    from.deactivate()
                    check(to.activate()) { "The SDK refused to start ${target.label()}" }
                }
                to.connection.first { it == TerminalConnection.CONNECTED }
            }
            if (connected == null) IllegalStateException("${target.label()} not connected after $SWITCH_TIMEOUT") else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
    }

    private suspend fun fallBackToStoreAndForward() {
        _state.update { it.copy(switchingTo = PaymentMode.OFFLINE) }
        connectTo(PaymentMode.OFFLINE)?.let { errors.report("switch to offline", it) }
        _state.update { it.copy(mode = PaymentMode.OFFLINE, switchingTo = null, onlineUnavailable = true) }
        holdOnlineAfterFailure()
    }

    /** Blocks online for the next wait in [RETRY_ONLINE_DELAYS]; replaces any earlier wait. */
    private fun holdOnlineAfterFailure() = synchronized(lock) {
        val wait = RETRY_ONLINE_DELAYS[failures.coerceAtMost(RETRY_ONLINE_DELAYS.lastIndex)]
        failures++
        retryJob?.cancel()
        control.update { it.copy(onlineBlocked = true) }
        retryJob = appScope.launch {
            delay(wait)
            control.update { it.copy(onlineBlocked = false) }
        }
    }

    private fun terminal(mode: PaymentMode): PaymentTerminal = when (mode) {
        PaymentMode.ONLINE -> online
        PaymentMode.OFFLINE -> saf
    }

    private fun PaymentMode.other(): PaymentMode = when (this) {
        PaymentMode.ONLINE -> PaymentMode.OFFLINE
        PaymentMode.OFFLINE -> PaymentMode.ONLINE
    }

    private fun PaymentMode.label(): String = when (this) {
        PaymentMode.ONLINE -> "the online API"
        PaymentMode.OFFLINE -> "Store and Forward"
    }

    private data class Control(
        /** A failed switch to online: wait before trying again. */
        val onlineBlocked: Boolean = false,
        /** The network has been up for [STABLE_NETWORK_FOR]. */
        val networkStable: Boolean = false,
        /** The last switch to Store and Forward came from a network drop. */
        val afterNetworkLoss: Boolean = false,
    )

    private data class Inputs(
        val network: NetworkStatus,
        val storedInJournal: Int,
        /** The terminal's last answer; `null` until it has answered once. */
        val summary: StoredSummary?,
    ) {
        fun waitingToSend(): Int = maxOf(storedInJournal, summary?.references?.size ?: 0)

        /** Nothing stored, as far as both the journal and the terminal's own answer say. */
        fun nothingWaiting(): Boolean = storedInJournal == 0 && summary?.references?.isEmpty() == true
    }

    companion object {
        /** Longest wait for the other API to be CONNECTED. */
        val SWITCH_TIMEOUT: Duration = 30.seconds

        /** Waits before the next try after 1, 2, 3, then 4 or more failed switches to online in a row. */
        val RETRY_ONLINE_DELAYS: List<Duration> = listOf(2.minutes, 5.minutes, 15.minutes, 30.minutes)

        /** After a network drop, how long the network must stay up before the app goes online again. */
        val STABLE_NETWORK_FOR: Duration = 60.seconds

        /** Message of the sale refused because a switch is running. */
        const val SWITCHING = "Switching between online and offline sales"
    }
}
