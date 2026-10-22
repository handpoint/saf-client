package com.handpoint.safclient.domain

import kotlinx.coroutines.flow.Flow
import java.util.Currency

/** Port over user-editable app settings. */
interface AppPreferences {
    val merchantName: Flow<String>
    val safCurrency: Flow<Currency>

    suspend fun setMerchantName(name: String)

    suspend fun setSafCurrency(currency: Currency)
}
