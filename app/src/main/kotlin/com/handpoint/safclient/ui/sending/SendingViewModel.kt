package com.handpoint.safclient.ui.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.usecase.SendStoredSales
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

sealed interface SendingUiState {
    /** The run has started; the terminal has not said how many sales it holds yet. */
    data object Starting : SendingUiState

    data class Running(val sent: Int, val total: Int) : SendingUiState

    /** The run has ended. [result] is `null` when there was nothing to send. */
    data class Done(val result: ForwardingResult?) : SendingUiState
}

enum class SendingRowStatus { SENT, WAITING, STILL_ON_TERMINAL }

/** One sale on the sending screen. */
data class SendingRow(val reference: SaleReference, val amount: Money, val status: SendingRowStatus)

/** `true` when sending again may help: the run stopped because the connection dropped. */
fun ForwardingResult?.canTryAgain(): Boolean = when (this) {
    is ForwardingResult.Partial -> error == ForwardingError.NETWORK
    is ForwardingResult.NoneSent -> error == ForwardingError.NETWORK
    is ForwardingResult.AllSent, null -> false
}

/**
 * Send now: starts a forwarding run when the screen opens (or joins the active one) and shows
 * its progress per sale.
 *
 * The run lives in the app scope, so leaving the screen does not stop it; Home shows it as sending.
 */
@HiltViewModel
class SendingViewModel @Inject constructor(
    private val sendStoredSales: SendStoredSales,
    private val journal: SalesJournal,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val _state = MutableStateFlow<SendingUiState>(SendingUiState.Starting)
    val state: StateFlow<SendingUiState> = _state.asStateFlow()

    private val _rows = MutableStateFlow<List<SendingRow>>(emptyList())

    /** The sales of the run: those stored when it started, plus any it reported sent. */
    val rows: StateFlow<List<SendingRow>> = _rows.asStateFlow()

    /**
     * `true` from the start of a run until its journal updates are done, which goes on a little
     * after the run's result is shown. Try again waits for it to turn `false`.
     */
    val isRunning: StateFlow<Boolean> = sendStoredSales.isRunning

    /** Approval time of each row's sale, to keep rows oldest first. */
    private val approvedAt = mutableMapOf<SaleReference, Instant>()

    private var run: Job = viewModelScope.launch { runOnce() }

    /**
     * Sends again after a run that ended. Ignored while a run is active. Waits for the previous
     * run to finish its journal updates first: joining it would only replay its result.
     */
    fun retry() {
        if (run.isActive || _state.value !is SendingUiState.Done) return
        run = viewModelScope.launch {
            isRunning.first { !it }
            _state.value = SendingUiState.Starting
            runOnce()
        }
    }

    private suspend fun runOnce() {
        try {
            // Read before the run marks any sale sent; sales already sent by a joined run come back as events.
            val pending = journal.pending()
            approvedAt.clear()
            pending.forEach { approvedAt[it.reference] = it.approvedAt }
            _rows.value = pending
                .sortedBy { it.approvedAt }
                .map { SendingRow(it.reference, it.amount, SendingRowStatus.WAITING) }
            sendStoredSales().collect(::onEvent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.report("send stored sales", e)
        }
        // A run always ends with a result; if none came (or it failed), say it could not be sent.
        if (_state.value !is SendingUiState.Done) finish(ForwardingResult.NoneSent(ForwardingError.FAILED, 0, 0, 0))
    }

    private suspend fun onEvent(event: ForwardingEvent) {
        when (event) {
            is ForwardingEvent.Started -> _state.value = SendingUiState.Running(sent = 0, total = event.total)
            is ForwardingEvent.Sent -> {
                markSent(event.reference)
                _state.value = SendingUiState.Running(sent = event.sent, total = event.total)
            }
            is ForwardingEvent.Finished -> finish(event.result)
            ForwardingEvent.NothingToSend -> finish(null)
        }
    }

    private suspend fun markSent(reference: SaleReference) {
        val rows = _rows.value
        if (rows.any { it.reference == reference }) {
            _rows.value = rows.map { if (it.reference == reference) it.copy(status = SendingRowStatus.SENT) else it }
        } else {
            // Sent by a run that was already active when the screen opened.
            val sale = journal.find(reference) ?: return
            approvedAt[reference] = sale.approvedAt
            _rows.value = (rows + SendingRow(reference, sale.amount, SendingRowStatus.SENT))
                .sortedBy { approvedAt[it.reference] }
        }
    }

    private fun finish(result: ForwardingResult?) {
        // Nothing on the terminal: any sale the journal still lists as stored is reconciled by the run.
        _rows.value = if (result == null) emptyList() else _rows.value.map {
            if (it.status == SendingRowStatus.WAITING) it.copy(status = SendingRowStatus.STILL_ON_TERMINAL) else it
        }
        _state.value = SendingUiState.Done(result)
    }
}
