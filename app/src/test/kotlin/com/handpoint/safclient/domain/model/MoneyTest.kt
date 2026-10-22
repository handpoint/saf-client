package com.handpoint.safclient.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Currency
import java.util.Locale

class MoneyTest {

    private val usd = Currency.getInstance("USD")
    private val eur = Currency.getInstance("EUR")

    @Test
    fun `formats USD minor units with two fraction digits`() {
        assertEquals("$12.50", Money(1250, usd).format())
    }

    @Test
    fun `formats zero USD`() {
        assertEquals("$0.00", Money(0, usd).format())
    }

    @Test
    fun `formats EUR in US locale`() {
        assertEquals("€1.00", Money(100, eur).format(Locale.US))
    }

    @Test
    fun `zero has no minor units in the given currency`() {
        assertEquals(Money(0, usd), Money.zero(usd))
    }

    @Test
    fun `adds amounts in the same currency`() {
        assertEquals(Money(1500, usd), Money(1250, usd) + Money(250, usd))
    }

    @Test
    fun `adding different currencies throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money(100, usd) + Money(100, eur)
        }
    }
}
