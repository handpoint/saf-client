package com.handpoint.safclient.ui.operations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.model.OperationKind
import com.handpoint.safclient.domain.reportingErrors
import com.handpoint.safclient.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class OperationsUiState(
    val form: OperationForm = OperationForm(),
    /** Settings has the surcharge on, with this percent. */
    val surchargeEnabled: Boolean = false,
    val surchargePercent: BigDecimal = BigDecimal.ZERO,
    /** The transaction ID of the last operation that ended, to paste into a linked refund. */
    val lastTransactionId: String? = null,
    val error: String? = null,
    /** The operations the tester can pick: the refund kinds for a sale of the history, the others otherwise. */
    val kinds: List<OperationKind> = OperationKind.entries.filterNot { it.fromHistory },
    /** The amount of the sale being refunded, to start the keypad with. */
    val prefillMinor: Long? = null,
)

/**
 * The New operation screen: the tester picks the operation and its extra fields, then goes on to
 * the keypad. The operation itself runs from the keypad flow, like a sale.
 */
@HiltViewModel
class OperationsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val errors: ErrorReporter,
    private val draft: OperationDraft,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val refundOf: String? = savedState.get<String>(Routes.OPERATIONS_TXN)

    private val _state = MutableStateFlow(
        if (refundOf != null) {
            // The Refund button of a sale: the refund kinds, with the sale's transaction ID.
            val moto = savedState.get<String>(Routes.OPERATIONS_MOTO).toBoolean()
            OperationsUiState(
                form = OperationForm(
                    kind = if (moto) OperationKind.MOTO_LINKED_REFUND else OperationKind.LINKED_REFUND,
                    originalTransactionId = refundOf,
                ),
                lastTransactionId = draft.lastTransactionId,
                kinds = REFUND_KINDS,
                prefillMinor = savedState.get<String>(Routes.OPERATIONS_AMOUNT)?.toLongOrNull(),
            )
        } else {
            // New operation: a refund left from the history is not offered here.
            val form = draft.form.takeUnless { it.kind.fromHistory } ?: OperationForm()
            OperationsUiState(form = form, lastTransactionId = draft.lastTransactionId)
        },
    )
    val state: StateFlow<OperationsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.surchargeEnabled, preferences.surchargePercent) { enabled, percent -> enabled to percent }
                .collect { (enabled, percent) ->
                    _state.update { it.copy(surchargeEnabled = enabled, surchargePercent = percent) }
                }
        }
    }

    fun edit(change: (OperationForm) -> OperationForm) = _state.update { it.copy(form = change(it.form), error = null) }

    /** Puts the transaction ID of the last operation into the form. */
    fun useLastTransactionId() {
        val id = _state.value.lastTransactionId ?: return
        edit { it.copy(originalTransactionId = id) }
    }

    /**
     * Keeps the choice for the keypad flow. Returns `false`, with an error on screen, when a
     * field is not a number or a linked operation has no transaction ID.
     *
     * A sale sends its tax fields through the sale pipeline (online or Store and Forward), so
     * they are saved in the preferences the sale reads.
     */
    fun proceed(): Boolean {
        val form = _state.value.form
        val extras = form.toExtras()
        val error = when {
            extras == null -> "An amount or a tax field is not a number."
            form.kind.needsOriginalId && form.originalTransactionId.isBlank() -> "Enter the original transaction ID."
            else -> null
        }
        if (extras == null || error != null) {
            _state.update { it.copy(error = error) }
            return false
        }
        draft.form = form
        if (form.kind == OperationKind.SALE) {
            viewModelScope.launch { errors.reportingErrors("save sale tax") { preferences.setSaleTax(extras.tax) } }
        }
        return true
    }

    private companion object {
        val REFUND_KINDS = listOf(
            OperationKind.LINKED_REFUND,
            OperationKind.MOTO_LINKED_REFUND,
            OperationKind.AUTOMATIC_REFUND,
        )
    }
}
