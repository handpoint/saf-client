package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.ModeState
import com.handpoint.safclient.domain.model.NetworkStatus
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
 * error, and tries online again after [RETRY_ONLINE_AFTER].
 *
 * Call [start] once (Home does); it runs in [appScope] for as long as the app runs.
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
    /** Guards the decision to switch against a sale that starts at the same moment. */
    private val lock = Any()

    private val started = AtomicBoolean(false)

    private val salesInProgress = MutableStateFlow(0)

    /** `true` for [RETRY_ONLINE_AFTER] after a failed switch to online. */
    private val onlineBlocked = MutableStateFlow(false)

    private val _state = MutableStateFlow(ModeState(network = network.status.value))

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
        val busy = combine(salesInProgress, send.isRunning, statuses.isActive) { _, _, _ -> Unit }
        appScope.launch {
            combine(
                network.status,
                journal.observeAll().map { sales -> sales.count { it.status == ProcessingStatus.STORED } }
                    .distinctUntilChanged(),
                reconcile.lastSummary,
                busy,
                onlineBlocked,
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

    private suspend fun evaluate(inputs: Inputs) {
        val target = synchronized(lock) {
            val state = _state.value
            val wanted = wantedMode(inputs)
            if (state.isSwitching || wanted == state.mode || isBusy()) return
            _state.value = state.copy(switchingTo = wanted)
            wanted
        }
        switchTo(target)
    }

    private fun wantedMode(inputs: Inputs): PaymentMode {
        if (inputs.network == NetworkStatus.OFFLINE) return PaymentMode.OFFLINE
        val nothingWaiting = inputs.storedInJournal == 0 && inputs.summary?.references?.isEmpty() == true
        return if (nothingWaiting && !onlineBlocked.value) PaymentMode.ONLINE else PaymentMode.OFFLINE
    }

    /** Read live, not from the inputs: they may be a moment old. */
    private fun isBusy(): Boolean =
        salesInProgress.value > 0 || send.isRunning.value || statuses.isActive.value

    private suspend fun switchTo(target: PaymentMode) {
        val from = terminal(target.other())
        val to = terminal(target)
        val error = try {
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
        if (error == null) {
            _state.update {
                it.copy(
                    mode = target,
                    switchingTo = null,
                    onlineUnavailable = if (target == PaymentMode.ONLINE) false else it.onlineUnavailable,
                )
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

    private suspend fun fallBackToStoreAndForward() {
        _state.update { it.copy(switchingTo = PaymentMode.OFFLINE) }
        try {
            withContext(io) {
                online.deactivate()
                saf.activate()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.report("switch to offline", e)
        }
        _state.update { it.copy(mode = PaymentMode.OFFLINE, switchingTo = null, onlineUnavailable = true) }
        onlineBlocked.value = true
        appScope.launch {
            delay(RETRY_ONLINE_AFTER)
            onlineBlocked.value = false
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

    private data class Inputs(
        val network: NetworkStatus,
        val storedInJournal: Int,
        /** The terminal's last answer; `null` until it has answered once. */
        val summary: StoredSummary?,
    ) {
        fun waitingToSend(): Int = maxOf(storedInJournal, summary?.references?.size ?: 0)
    }

    companion object {
        /** Longest wait for the other API to be CONNECTED. */
        val SWITCH_TIMEOUT: Duration = 30.seconds

        /** Wait after a failed switch to online before trying again. */
        val RETRY_ONLINE_AFTER: Duration = 2.minutes

        /** Message of the sale refused because a switch is running. */
        const val SWITCHING = "Switching between online and offline sales"
    }
}
