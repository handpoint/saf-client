package com.handpoint.safclient.domain.model

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

data class Money(val minorUnits: Long, val currency: Currency) {

    fun format(locale: Locale = Locale.US): String {
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            currency = this@Money.currency
        }
        return formatter.format(BigDecimal.valueOf(minorUnits, currency.defaultFractionDigits))
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) {
            "Cannot add ${other.currency} to $currency"
        }
        return Money(minorUnits + other.minorUnits, currency)
    }

    companion object {
        fun zero(currency: Currency): Money = Money(0L, currency)
    }
}
