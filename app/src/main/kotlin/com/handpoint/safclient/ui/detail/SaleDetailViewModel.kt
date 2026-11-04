package com.handpoint.safclient.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handpoint.safclient.di.IoDispatcher
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.OperationKind
import com.handpoint.safclient.domain.model.OperationReport
import com.handpoint.safclient.domain.model.OperationRequest
import com.handpoint.safclient.domain.model.total
import com.handpoint.safclient.domain.usecase.RunOperation
import kotlinx.coroutines.CancellationException
import java.time.Clock
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.reportingErrors
import com.handpoint.safclient.domain.usecase.DeleteStoredSale
import com.handpoint.safclient.domain.usecase.RefreshSaleStatus
import com.handpoint.safclient.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the sale detail screen shows. */
data class SaleDetailUiState(
    /** The sale; `null` while loading, or when the journal does not have it. */
    val sale: Sale? = null,
    val refreshing: Boolean = false,
    /** Only a sale still stored on the terminal (never charged) can be deleted. */
    val canDelete: Boolean = false,
    /** One-off message for a snackbar; call [SaleDetailViewModel.onMessageShown] once shown. */
    val message: String? = null,
    val isLoading: Boolean = true,
    val deleting: Boolean = false,
    /**
     * Store and Forward is in use, so Handpoint can be asked about an offline sale. While the
     * app takes online sales, Refresh status waits for offline mode.
     */
    val storeAndForwardActive: Boolean = true,
    /** A reversal is running on the terminal. */
    val operationRunning: Boolean = false,
    /** What the SDK answered to the last reversal; shown until the merchant closes it. */
    val operationReport: OperationReport? = null,
) {
    /**
     * Only an offline sale has a status to ask Handpoint for: an online sale was settled when
     * it was approved.
     */
    val canRefresh: Boolean get() = sale?.channel == SaleChannel.OFFLINE

    /** An online sale whose outcome the app could not confirm: the merchant checks with Handpoint. */
    val isUnconfirmed: Boolean get() = sale?.status == ProcessingStatus.UNKNOWN

    /** A charged sale can be refunded: the operations screen opens with its transaction ID. */
    val canRefund: Boolean get() = sale?.status == ProcessingStatus.SUCCEEDED || sale?.status == ProcessingStatus.CAPTURED

    /** The journal has no sale with this reference. */
    val notFound: Boolean get() = !isLoading && sale == null && !deleting
}

/** One sale: its status, a status refresh, and delete while it is still stored. */
@HiltViewModel
class SaleDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val journal: SalesJournal,
    private val refresh: RefreshSaleStatus,
    private val delete: DeleteStoredSale,
    payments: PaymentModeCoordinator,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val errors: ErrorReporter,
    private val runOperation: RunOperation,
    private val clock: Clock,
) : ViewModel() {

    private val reference = SaleReference(checkNotNull(savedStateHandle.get<String>(Routes.SALE_REFERENCE)))

    private val refreshing = MutableStateFlow(false)
    private val deleting = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val operation = MutableStateFlow<Pair<Boolean, OperationReport?>>(false to null)

    val uiState: StateFlow<SaleDetailUiState> = combine(
        journal.observe(reference),
        refreshing,
        deleting,
        message,
        combine(payments.state, operation) { mode, operation -> mode to operation },
    ) { sale, refreshing, deleting, message, (mode, operation) ->
        SaleDetailUiState(
            sale = sale,
            refreshing = refreshing,
            canDelete = sale?.status == ProcessingStatus.STORED,
            message = message,
            isLoading = false,
            deleting = deleting,
            storeAndForwardActive = mode.isStoreAndForwardReady,
            operationRunning = operation.first,
            operationReport = operation.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SaleDetailUiState())

    private val _deleted = Channel<Unit>(Channel.BUFFERED)

    /** Emits once the sale is gone from the terminal, so the screen can go back. */
    val deleted: Flow<Unit> = _deleted.receiveAsFlow()

    /**
     * Reverses the sale (cancels it, with the MoTo call for a MoTo sale). An approval marks the
     * sale as cancelled in the history. The answer stays in [SaleDetailUiState.operationReport].
     */
    fun reverse() {
        val sale = uiState.value.sale ?: return
        if (operation.value.first || !uiState.value.canRefund) return
        operation.value = true to null
        viewModelScope.launch {
            val report = try {
                withContext(io) {
                    val request = OperationRequest(
                        kind = if (sale.isMoto) OperationKind.MOTO_REVERSAL else OperationKind.REVERSAL,
                        amount = sale.total,
                        originalTransactionId = sale.transactionId ?: sale.reference.value,
                    )
                    runOperation(request).also {
                        if (it.approved) journal.updateStatus(reference, ProcessingStatus.CANCELLED, clock.instant())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.report("reversal", e)
                OperationReport("The reversal failed: ${e.message}")
            }
            operation.value = false to report
        }
    }

    fun dismissOperationReport() {
        operation.value = false to null
    }

    /** Asks Handpoint for the sale's status. A tap while a refresh runs is ignored. */
    fun refreshStatus() {
        val state = uiState.value
        if (refreshing.value || !state.canRefresh || !state.storeAndForwardActive) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                val status = errors.reportingErrors("refresh status") { withContext(io) { refresh(reference) } }
                if (status == null) message.value = REFRESH_FAILED
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Deletes the sale from the terminal. Call after the merchant has confirmed. */
    fun deleteSale() {
        if (deleting.value || !uiState.value.canDelete) return
        deleting.value = true
        viewModelScope.launch {
            val result = errors.reportingErrors("delete") { withContext(io) { delete(reference) } }
                ?: DeleteResult.ERROR
            when (result) {
                // Leave `deleting` on: the screen is about to close.
                DeleteResult.DELETED -> _deleted.send(Unit)
                // The sale left the terminal before the delete, so it was most likely charged:
                // the journal now shows it as sent, and the screen stays to show that.
                DeleteResult.NOT_ON_TERMINAL -> {
                    message.value = ALREADY_SENT
                    deleting.value = false
                }
                DeleteResult.ERROR -> {
                    message.value = DELETE_FAILED
                    deleting.value = false
                }
            }
        }
    }

    fun onMessageShown() {
        message.value = null
    }

    companion object {
        const val REFRESH_FAILED = "Couldn't reach Handpoint. Try again later."
        const val DELETE_FAILED = "Couldn't delete the sale. Try again."
        const val ALREADY_SENT = "This sale was already sent to Handpoint and can't be deleted."
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
