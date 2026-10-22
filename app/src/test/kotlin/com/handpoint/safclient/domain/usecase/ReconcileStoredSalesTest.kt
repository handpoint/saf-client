package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.STORED
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency

class ReconcileStoredSalesTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val terminal = FakeTerminal()
    private val journal = InMemoryJournal(sale("A"), sale("B"), sale("C"))
    private val settings = FakePreferences(currency = Currency.getInstance("EUR"))
    private val reconcile = ReconcileStoredSales(terminal, journal, settings, clock)

    @Test
    fun `stored sales no longer on the terminal become in progress`() = runTest {
        terminal.storedSummary = StoredSummary(listOf(SaleReference("A")), Money(1000, USD))

        reconcile()

        assertEquals(STORED, journal.status("A"))
        assertEquals(IN_PROGRESS, journal.status("B"))
        assertEquals(IN_PROGRESS, journal.status("C"))
        assertEquals(clock.instant(), journal.find(SaleReference("B"))?.sentAt)
    }

    @Test
    fun `returns the terminal total and remembers its currency`() = runTest {
        terminal.storedSummary = StoredSummary(listOf(SaleReference("A")), Money(1000, USD))

        val total = reconcile()

        assertEquals(Money(1000, USD), total)
        assertEquals(USD, settings.safCurrency.value)
    }

    @Test
    fun `no answer from the terminal changes nothing`() = runTest {
        terminal.storedSummary = null

        val total = reconcile()

        assertNull(total)
        assertEquals(listOf(STORED, STORED, STORED), listOf("A", "B", "C").map { journal.status(it) })
        assertEquals(Currency.getInstance("EUR"), settings.safCurrency.value)
    }

    @Test
    fun `empty terminal with no total keeps the currency`() = runTest {
        terminal.storedSummary = StoredSummary(emptyList(), null)

        val total = reconcile()

        assertNull(total)
        assertEquals(Currency.getInstance("EUR"), settings.safCurrency.value)
        assertEquals(listOf(IN_PROGRESS, IN_PROGRESS, IN_PROGRESS), listOf("A", "B", "C").map { journal.status(it) })
    }
}
