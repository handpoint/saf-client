package com.handpoint.safclient.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.handpoint.safclient.domain.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Currency

class AppSettings(
    private val dataStore: DataStore<Preferences>,
    private val defaultCurrencyCode: String,
) : AppPreferences {

    override val merchantName: Flow<String> =
        dataStore.data.map { it[MERCHANT_NAME] ?: DEFAULT_MERCHANT_NAME }

    override val safCurrency: Flow<Currency> =
        dataStore.data.map { Currency.getInstance(it[SAF_CURRENCY] ?: defaultCurrencyCode) }

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
    }
}
