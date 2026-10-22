package com.handpoint.safclient.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handpoint.safclient.di.IoDispatcher
import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.domain.reportingErrors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Currency
import javax.inject.Inject

/** What the settings screen shows. */
data class SettingsUiState(
    val merchantName: String = "",
    /** The terminal's offline currency; `null` until it loads. */
    val currency: Currency? = null,
    val connection: TerminalConnection = TerminalConnection.DISCONNECTED,
    /** A connect started here is still running. */
    val connecting: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val payments: PaymentModeCoordinator,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val connecting = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.merchantName,
        preferences.safCurrency,
        payments.connection,
        connecting,
    ) { name, currency, connection, connecting ->
        SettingsUiState(name, currency, connection, connecting)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(connection = payments.connection.value),
    )

    /**
     * Saves the merchant name, trimmed and cut to [MERCHANT_NAME_MAX] characters.
     * Returns `false`, and saves nothing, when the name is blank.
     */
    fun saveMerchantName(name: String): Boolean {
        val cleaned = name.trim().take(MERCHANT_NAME_MAX).trimEnd()
        if (cleaned.isEmpty()) return false
        viewModelScope.launch {
            errors.reportingErrors("save merchant name") { preferences.setMerchantName(cleaned) }
        }
        return true
    }

    /**
     * Connects the payment API in use (online or Store and Forward). Ignored while a connect
     * runs, while the app switches API, or when it is already connected.
     */
    fun connect() {
        if (connecting.value || payments.connection.value == TerminalConnection.CONNECTED) return
        connecting.value = true
        viewModelScope.launch {
            try {
                // The first connect() creates the SDK, which is heavy and can fail.
                errors.reportingErrors("connect") { withContext(io) { payments.connect() } }
            } finally {
                connecting.value = false
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            errors.reportingErrors("disconnect") { withContext(io) { payments.disconnect() } }
        }
    }

    companion object {
        const val MERCHANT_NAME_MAX = 40
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
