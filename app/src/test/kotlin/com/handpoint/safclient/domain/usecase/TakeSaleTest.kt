package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TakeSaleTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val terminal = FakeTerminal()
    private val journal = InMemoryJournal()
    private val reconcile = ReconcileStoredSales(terminal, journal, FakePreferences(), clock)
    private val takeSale = TakeSale(terminal, journal, reconcile)

    @Test
    fun `approved sale is recorded in the journal`() = runTest {
        val approved = sale("A")
        terminal.saleOutcome = SaleOutcome.ApprovedOffline(approved)

        val outcome = takeSale(Money(1000, USD))

        assertEquals(SaleOutcome.ApprovedOffline(approved), outcome)
        assertEquals(approved, journal.find(SaleReference("A")))
        assertEquals(1, terminal.saleCalls)
    }

    @Test
    fun `declined sale is not recorded`() = runTest {
        terminal.saleOutcome = SaleOutcome.Declined(DeclineReason.CARD_EXPIRED)

        val outcome = takeSale(Money(1000, USD))

        assertEquals(SaleOutcome.Declined(DeclineReason.CARD_EXPIRED), outcome)
        assertTrue(journal.observeAll().first().isEmpty())
        assertEquals(0, terminal.storedCalls)
    }

    @Test
    fun `unknown outcome reconciles the journal with the terminal`() = runTest {
        journal.record(sale("OLD"))
        terminal.saleOutcome = SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN)
        terminal.storedSummary = StoredSummary(emptyList(), null)

        val outcome = takeSale(Money(1000, USD))

        assertEquals(SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN), outcome)
        assertEquals(1, terminal.storedCalls)
        assertEquals(IN_PROGRESS, journal.status("OLD"))
    }

    @Test
    fun `other failures do not reconcile`() = runTest {
        terminal.saleOutcome = SaleOutcome.Failed("Terminal busy")

        takeSale(Money(1000, USD))

        assertEquals(0, terminal.storedCalls)
        assertTrue(journal.observeAll().first().isEmpty())
    }
}
