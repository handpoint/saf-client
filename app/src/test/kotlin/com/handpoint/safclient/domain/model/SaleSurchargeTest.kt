package com.handpoint.safclient.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Currency

class SaleSurchargeTest {

    private val usd = Currency.getInstance("USD")

    @Test
    fun `three percent of fifty dollars is a dollar fifty`() {
        val surcharge = SaleSurcharge.of(Money(5000, usd), BigDecimal("3"))

        assertEquals(Money(150, usd), surcharge.sent)
        assertEquals(Money(5150, usd), surcharge.totalFor(Money(5000, usd)))
    }

    @Test
    fun `the amount is rounded half up to the minor unit`() {
        // 3% of 0.50 is 1.5 cents: up to 2. 3% of 0.33 is 0.99 cents: up to 1. 3% of 0.10 is 0.3: down to 0.
        assertEquals(2L, SaleSurcharge.of(Money(50, usd), BigDecimal("3")).sent.minorUnits)
        assertEquals(1L, SaleSurcharge.of(Money(33, usd), BigDecimal("3")).sent.minorUnits)
        assertEquals(0L, SaleSurcharge.of(Money(10, usd), BigDecimal("3")).sent.minorUnits)
    }

    @Test
    fun `a fractional percent works`() {
        assertEquals(Money(125, usd), SaleSurcharge.of(Money(5000, usd), BigDecimal("2.5")).sent)
    }

    @Test
    fun `a surcharge the gateway removed is not part of the total`() {
        val removed = SaleSurcharge.of(Money(5000, usd), BigDecimal("3")).withResult(applied = false, reason = "Debit card")

        assertEquals(Money(5000, usd), removed.totalFor(Money(5000, usd)))
    }

    @Test
    fun `a surcharge with no answer still counts in the total`() {
        val pending = SaleSurcharge.of(Money(5000, usd), BigDecimal("3"))

        assertEquals(null, pending.applied)
        assertEquals(Money(5150, usd), pending.totalFor(Money(5000, usd)))
    }

    @Test
    fun `a blank reason is no reason`() {
        assertEquals(null, SaleSurcharge.of(Money(5000, usd), BigDecimal("3")).withResult(true, " ").reason)
    }

    @Test
    fun `percent has no trailing zeros`() {
        assertEquals("3%", BigDecimal("3.00").formatPercent())
        assertEquals("2.5%", BigDecimal("2.50").formatPercent())
        assertEquals("10%", BigDecimal("10").formatPercent())
    }
}
