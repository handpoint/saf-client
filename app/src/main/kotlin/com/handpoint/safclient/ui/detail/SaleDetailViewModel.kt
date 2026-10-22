package com.handpoint.safclient.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handpoint.safclient.di.IoDispatcher
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ProcessingStatus
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
) {
    /** The journal has no sale with this reference. */
    val notFound: Boolean get() = !isLoading && sale == null && !deleting
}

/** One sale: its status, a status refresh, and delete while it is still stored. */
@HiltViewModel
class SaleDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    journal: SalesJournal,
    private val refresh: RefreshSaleStatus,
    private val delete: DeleteStoredSale,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val reference = SaleReference(checkNotNull(savedStateHandle.get<String>(Routes.SALE_REFERENCE)))

    private val refreshing = MutableStateFlow(false)
    private val deleting = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SaleDetailUiState> = combine(
        journal.observe(reference),
        refreshing,
        deleting,
        message,
    ) { sale, refreshing, deleting, message ->
        SaleDetailUiState(
            sale = sale,
            refreshing = refreshing,
            canDelete = sale?.status == ProcessingStatus.STORED,
            message = message,
            isLoading = false,
            deleting = deleting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SaleDetailUiState())

    private val _deleted = Channel<Unit>(Channel.BUFFERED)

    /** Emits once the sale is gone from the terminal, so the screen can go back. */
    val deleted: Flow<Unit> = _deleted.receiveAsFlow()

    /** Asks Handpoint for the sale's status. A tap while a refresh runs is ignored. */
    fun refreshStatus() {
        if (refreshing.value) return
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
                DeleteResult.DELETED, DeleteResult.NOT_ON_TERMINAL -> _deleted.send(Unit)
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
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
