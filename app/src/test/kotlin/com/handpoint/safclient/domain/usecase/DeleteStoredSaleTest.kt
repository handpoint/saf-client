package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.model.DeleteResult
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

class DeleteStoredSaleTest {
    private val terminal = FakeTerminal()
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)

    private fun delete(journal: InMemoryJournal, reconcile: ReconcileStoredSales = reconcile(journal)) =
        DeleteStoredSale(terminal, journal, reconcile, clock)

    private fun reconcile(journal: InMemoryJournal) = ReconcileStoredSales(terminal, journal, FakePreferences(), clock)

    @Test
    fun `a sale gone from the terminal leaves the last terminal summary`() = runTest {
        for (answer in listOf(DeleteResult.DELETED, DeleteResult.NOT_ON_TERMINAL)) {
            val journal = InMemoryJournal(sale("A", STORED, 1000), sale("B", STORED, 2000))
            val reconcile = reconcile(journal)
            terminal.storedSummary = StoredSummary(listOf(SaleReference("A"), SaleReference("B")), Money(3000, USD))
            reconcile()
            terminal.deleteResult = answer

            delete(journal, reconcile)(SaleReference("A"))

            assertEquals(StoredSummary(listOf(SaleReference("B")), Money(2000, USD)), reconcile.lastSummary.value)
        }
    }

    @Test
    fun `deleted on the terminal removes the stored sale`() = runTest {
        val journal = InMemoryJournal(sale("A", STORED))
        terminal.deleteResult = DeleteResult.DELETED

        val result = delete(journal)(SaleReference("A"))

        assertEquals(DeleteResult.DELETED, result)
        assertNull(journal.find(SaleReference("A")))
    }

    @Test
    fun `not on the terminal keeps the sale and marks it sent`() = runTest {
        val journal = InMemoryJournal(sale("A", STORED))
        terminal.deleteResult = DeleteResult.NOT_ON_TERMINAL

        val result = delete(journal)(SaleReference("A"))

        assertEquals(DeleteResult.NOT_ON_TERMINAL, result)
        assertEquals(IN_PROGRESS, journal.status("A"))
        assertEquals(clock.instant(), journal.find(SaleReference("A"))?.sentAt)
    }

    @Test
    fun `sale already sent is kept`() = runTest {
        val journal = InMemoryJournal(sale("A", IN_PROGRESS))
        terminal.deleteResult = DeleteResult.NOT_ON_TERMINAL

        delete(journal)(SaleReference("A"))

        assertEquals(IN_PROGRESS, journal.status("A"))
    }

    @Test
    fun `error keeps the sale`() = runTest {
        val journal = InMemoryJournal(sale("A", STORED))
        terminal.deleteResult = DeleteResult.ERROR

        val result = delete(journal)(SaleReference("A"))

        assertEquals(DeleteResult.ERROR, result)
        assertEquals(STORED, journal.status("A"))
    }
}
