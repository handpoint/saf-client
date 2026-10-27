package com.handpoint.safclient.domain

import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.util.Currency

/** Port over user-editable app settings. */
interface AppPreferences {
    val merchantName: Flow<String>
    val safCurrency: Flow<Currency>

    /** Whether sales ask for a surcharge. Off by default: the usual flow stays as it is. */
    val surchargeEnabled: Flow<Boolean>

    /** The surcharge, as a percent of the sale amount (3 means 3%). */
    val surchargePercent: Flow<BigDecimal>

    suspend fun setMerchantName(name: String)

    suspend fun setSurchargeEnabled(enabled: Boolean)

    suspend fun setSurchargePercent(percent: BigDecimal)

    suspend fun setSafCurrency(currency: Currency)
}
