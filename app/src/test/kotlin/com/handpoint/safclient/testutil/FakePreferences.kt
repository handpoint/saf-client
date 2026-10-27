package com.handpoint.safclient.testutil

import com.handpoint.safclient.domain.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.math.BigDecimal
import java.util.Currency

/** [AppPreferences] kept in memory. */
class FakePreferences(currency: Currency = Currency.getInstance("USD")) : AppPreferences {
    override val merchantName = MutableStateFlow("My Store")
    override val safCurrency = MutableStateFlow(currency)
    override val surchargeEnabled = MutableStateFlow(false)
    override val surchargePercent = MutableStateFlow(BigDecimal("3"))

    override suspend fun setMerchantName(name: String) {
        merchantName.value = name
    }

    override suspend fun setSurchargeEnabled(enabled: Boolean) {
        surchargeEnabled.value = enabled
    }

    override suspend fun setSurchargePercent(percent: BigDecimal) {
        surchargePercent.value = percent
    }

    override suspend fun setSafCurrency(currency: Currency) {
        safCurrency.value = currency
    }
}
