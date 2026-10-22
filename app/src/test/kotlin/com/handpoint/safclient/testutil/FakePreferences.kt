package com.handpoint.safclient.testutil

import com.handpoint.safclient.domain.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Currency

/** [AppPreferences] kept in memory. */
class FakePreferences(currency: Currency = Currency.getInstance("USD")) : AppPreferences {
    override val merchantName = MutableStateFlow("My Store")
    override val safCurrency = MutableStateFlow(currency)

    override suspend fun setMerchantName(name: String) {
        merchantName.value = name
    }

    override suspend fun setSafCurrency(currency: Currency) {
        safCurrency.value = currency
    }
}
