package com.handpoint.safclient.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.handpoint.safclient.domain.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.util.Currency

class AppSettings(
    private val dataStore: DataStore<Preferences>,
    private val defaultCurrencyCode: String,
) : AppPreferences {

    override val merchantName: Flow<String> =
        dataStore.data.map { it[MERCHANT_NAME] ?: DEFAULT_MERCHANT_NAME }

    override val safCurrency: Flow<Currency> =
        dataStore.data.map { Currency.getInstance(it[SAF_CURRENCY] ?: defaultCurrencyCode) }

    override val surchargeEnabled: Flow<Boolean> =
        dataStore.data.map { it[SURCHARGE_ENABLED] ?: false }

    override val surchargePercent: Flow<BigDecimal> =
        dataStore.data.map { prefs -> prefs[SURCHARGE_PERCENT]?.toBigDecimalOrNull() ?: DEFAULT_SURCHARGE_PERCENT }

    override suspend fun setSurchargeEnabled(enabled: Boolean) {
        dataStore.edit { it[SURCHARGE_ENABLED] = enabled }
    }

    override suspend fun setSurchargePercent(percent: BigDecimal) {
        dataStore.edit { it[SURCHARGE_PERCENT] = percent.toPlainString() }
    }

    override suspend fun setMerchantName(name: String) {
        dataStore.edit { it[MERCHANT_NAME] = name }
    }

    override suspend fun setSafCurrency(currency: Currency) {
        dataStore.edit { it[SAF_CURRENCY] = currency.currencyCode }
    }

    private companion object {
        const val DEFAULT_MERCHANT_NAME = "My Store"
        val MERCHANT_NAME = stringPreferencesKey("merchant_name")
        val SAF_CURRENCY = stringPreferencesKey("saf_currency")
        val SURCHARGE_ENABLED = booleanPreferencesKey("surcharge_enabled")
        val SURCHARGE_PERCENT = stringPreferencesKey("surcharge_percent")
        val DEFAULT_SURCHARGE_PERCENT = BigDecimal("3")
    }
}
