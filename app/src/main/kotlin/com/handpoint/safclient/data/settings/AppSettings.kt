package com.handpoint.safclient.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.model.SaleTax
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

    override val saleTax: Flow<SaleTax> = dataStore.data.map { prefs ->
        SaleTax(
            legacyTaxMinor = prefs[LEGACY_TAX],
            level2TaxMinor = prefs[LEVEL2_TAX],
            taxExempt = prefs[TAX_EXEMPT] ?: false,
            purchaseOrder = prefs[PURCHASE_ORDER]?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun setSaleTax(tax: SaleTax) {
        dataStore.edit { prefs ->
            prefs.setOrRemove(LEGACY_TAX, tax.legacyTaxMinor)
            prefs.setOrRemove(LEVEL2_TAX, tax.level2TaxMinor)
            prefs[TAX_EXEMPT] = tax.taxExempt
            prefs.setOrRemove(PURCHASE_ORDER, tax.purchaseOrder)
        }
    }

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

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.setOrRemove(
        key: Preferences.Key<T>,
        value: T?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        val LEGACY_TAX = longPreferencesKey("legacy_tax_minor")
        val LEVEL2_TAX = longPreferencesKey("level2_tax_minor")
        val TAX_EXEMPT = booleanPreferencesKey("tax_exempt")
        val PURCHASE_ORDER = stringPreferencesKey("purchase_order")
        const val DEFAULT_MERCHANT_NAME = "My Store"
        val MERCHANT_NAME = stringPreferencesKey("merchant_name")
        val SAF_CURRENCY = stringPreferencesKey("saf_currency")
        val SURCHARGE_ENABLED = booleanPreferencesKey("surcharge_enabled")
        val SURCHARGE_PERCENT = stringPreferencesKey("surcharge_percent")
        val DEFAULT_SURCHARGE_PERCENT = BigDecimal("3")
    }
}
