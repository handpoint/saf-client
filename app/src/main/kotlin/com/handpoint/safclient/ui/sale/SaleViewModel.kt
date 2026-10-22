package com.handpoint.safclient.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handpoint.safclient.di.IoDispatcher
import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.domain.model.approvedSale
import com.handpoint.safclient.domain.usecase.TakeSale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Currency
import javax.inject.Inject

/** Where the charge flow is: entering the amount, waiting for the card, or showing the outcome. */
sealed interface ChargeUiState {
    data object Idle : ChargeUiState

    /** The sale runs on the terminal. [step] is `null` until the terminal shows its first prompt. */
    data class InProgress(val step: CardStep?) : ChargeUiState

    data class Result(val outcome: SaleOutcome) : ChargeUiState
}

/**
 * The charge flow: amount entry, the sale on the terminal, and its outcome.
 *
 * The sale runs in [viewModelScope], so it survives a configuration change. The screen blocks
 * back while the sale runs, so the merchant cannot leave a sale behind.
 *
 * If the task is killed mid-sale, the sale is lost to this app: the terminal may still store
 * it, but the next reconcile on Home does not add it to the journal (only the SDK's start
 * result carries the sale's details). A partner app should keep its own record of a sale
 * before it starts, so it can match it later.
 *
 * Any error while the sale runs ends as an unknown outcome, never as a plain failure: the
 * terminal may already hold the sale, and offering Try again could charge the card twice.
 */
@HiltViewModel
class SaleViewModel @Inject constructor(
    private val takeSale: TakeSale,
    private val terminal: StoreAndForwardTerminal,
    settings: AppPreferences,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val minorUnits = MutableStateFlow(0L)

    /** The amount entered so far, in the currency the terminal takes offline sales in. */
    val amount: StateFlow<Money> = combine(minorUnits, settings.safCurrency, ::Money)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Money.zero(DEFAULT_CURRENCY))

    private val _state = MutableStateFlow<ChargeUiState>(ChargeUiState.Idle)
    val state: StateFlow<ChargeUiState> = _state.asStateFlow()

    val connection: StateFlow<TerminalConnection> = terminal.connection

    private val _printing = MutableStateFlow(false)

    /** `true` while a receipt is being printed; the print action ignores taps meanwhile. */
    val printing: StateFlow<Boolean> = _printing.asStateFlow()

    private val _entryRejected = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits when a key press is ignored because the amount would pass [MAX_MINOR_UNITS]. */
    val entryRejected: SharedFlow<Unit> = _entryRejected.asSharedFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off messages for a snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun onDigit(digit: Int) {
        require(digit in 0..9) { "Not a digit: $digit" }
        enter { it * 10 + digit }
    }

    fun onDoubleZero() = enter { it * 100 }

    fun onBackspace() = enter { it / 10 }

    private inline fun enter(change: (Long) -> Long) {
        if (_state.value != ChargeUiState.Idle) return
        val next = change(minorUnits.value)
        if (next > MAX_MINOR_UNITS) {
            _entryRejected.tryEmit(Unit)
        } else {
            minorUnits.value = next
        }
    }

    /** Starts the sale. Ignored at zero and while a sale runs, so a double tap starts one sale. */
    fun charge() {
        if (_state.value != ChargeUiState.Idle) return
        val money = amount.value
        if (money.minorUnits <= 0) return
        _state.value = ChargeUiState.InProgress(step = null)
        viewModelScope.launch {
            // Card steps are hot with no replay: listen before the sale starts.
            val steps = launch(start = CoroutineStart.UNDISPATCHED) {
                terminal.cardSteps.collect { _state.value = ChargeUiState.InProgress(it) }
            }
            val outcome = try {
                withContext(io) { takeSale(money) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The terminal may have stored the sale before the error (journal or reconcile failed).
                errors.report("sale", e)
                SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN)
            } finally {
                steps.cancel()
            }
            if (outcome == SaleOutcome.Cancelled) {
                _state.value = ChargeUiState.Idle
                _messages.trySend("Sale cancelled.")
            } else {
                _state.value = ChargeUiState.Result(outcome)
            }
        }
    }

    /** Asks the terminal to stop the sale. Only works while the current card step allows it. */
    fun cancel() {
        val current = _state.value as? ChargeUiState.InProgress ?: return
        if (current.step?.cancelAllowed != true) return
        viewModelScope.launch {
            val cancelled = try {
                withContext(io) { terminal.cancelSale() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.report("cancel sale", e)
                false
            }
            if (!cancelled) _messages.trySend("Can't cancel now — the card has been read.")
        }
    }

    /**
     * Back to the amount screen with the same amount, after a decline or failure. Ignored after an
     * approval or an unknown outcome, where a second charge could take the money twice.
     */
    fun tryAgain() {
        val result = _state.value as? ChargeUiState.Result ?: return
        if (resultCopy(result.outcome, amount.value.currency).canTryAgain) _state.value = ChargeUiState.Idle
    }

    /** Prints the customer receipt of the approved sale (or the merchant one when there is none). */
    fun printReceipt() {
        val sale = (_state.value as? ChargeUiState.Result)?.outcome?.approvedSale ?: return
        if (_printing.value) return
        _printing.value = true
        viewModelScope.launch {
            val receipt = sale.customerReceipt?.takeIf { it.isNotBlank() }
                ?: sale.merchantReceipt?.takeIf { it.isNotBlank() }
            val printed = try {
                receipt != null && withContext(io) { terminal.printReceipt(receipt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.report("print receipt", e)
                false
            } finally {
                _printing.value = false
            }
            if (!printed) _messages.trySend("Couldn't print the receipt.")
        }
    }

    companion object {
        /** Largest amount the keypad accepts, in minor units ($99,999.99 in USD). */
        const val MAX_MINOR_UNITS = 9_999_999L

        private val DEFAULT_CURRENCY: Currency = Currency.getInstance("USD")
    }
}
