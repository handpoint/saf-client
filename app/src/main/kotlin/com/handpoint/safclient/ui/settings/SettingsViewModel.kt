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
import java.math.BigDecimal
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
    /** Sales ask for a surcharge (and show the surcharge notice). */
    val surchargeEnabled: Boolean = false,
    /** The surcharge as a percent of the sale amount (3 means 3%). */
    val surchargePercent: BigDecimal = BigDecimal("3"),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val payments: PaymentModeCoordinator,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val connecting = MutableStateFlow(false)

    private val surcharge = combine(preferences.surchargeEnabled, preferences.surchargePercent, ::Pair)

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.merchantName,
        preferences.safCurrency,
        payments.connection,
        connecting,
        surcharge,
    ) { name, currency, connection, connecting, (surchargeEnabled, surchargePercent) ->
        SettingsUiState(name, currency, connection, connecting, surchargeEnabled, surchargePercent)
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

    fun setSurchargeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            errors.reportingErrors("save surcharge setting") { preferences.setSurchargeEnabled(enabled) }
        }
    }

    /**
     * Saves the surcharge percent. Returns `false`, and saves nothing, unless [text] is a number
     * above 0 and up to 100 with at most two decimals. A percent above [SURCHARGE_CAP_PERCENT] is
     * saved too (a test of the gateway's refusal needs it): the screen warns about it.
     */
    fun saveSurchargePercent(text: String): Boolean {
        val percent = parseSurchargePercent(text) ?: return false
        viewModelScope.launch {
            errors.reportingErrors("save surcharge percent") { preferences.setSurchargePercent(percent) }
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

        /** The usual legal maximum of a card surcharge in the US (3%). */
        val SURCHARGE_CAP_PERCENT = BigDecimal("3")

        /** A percent above 0 and up to 100, with at most two decimals; `null` for anything else. */
        fun parseSurchargePercent(text: String): BigDecimal? {
            val percent = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
            val valid = percent.signum() > 0 && percent <= BigDecimal(100) && percent.stripTrailingZeros().scale() <= 2
            return percent.takeIf { valid }
        }
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
