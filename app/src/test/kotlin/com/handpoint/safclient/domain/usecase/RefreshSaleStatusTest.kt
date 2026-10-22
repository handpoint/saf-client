package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.STORED
import com.handpoint.safclient.domain.model.ProcessingStatus.SUCCEEDED
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RefreshSaleStatusTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val terminal = FakeTerminal()

    private fun refresh(journal: InMemoryJournal) = RefreshSaleStatus(terminal, journal, clock)

    @Test
    fun `new status from the gateway is saved`() = runTest {
        val journal = InMemoryJournal(sale("A", IN_PROGRESS))
        terminal.statusAnswer = SUCCEEDED

        val status = refresh(journal)(SaleReference("A"))

        assertEquals(SUCCEEDED, status)
        assertEquals(SUCCEEDED, journal.status("A"))
        assertEquals(clock.instant(), journal.find(SaleReference("A"))?.statusCheckedAt)
    }

    @Test
    fun `succeeded stays succeeded when the terminal says stored`() = runTest {
        val journal = InMemoryJournal(sale("A", SUCCEEDED))
        terminal.statusAnswer = STORED

        refresh(journal)(SaleReference("A"))

        assertEquals(SUCCEEDED, journal.status("A"))
    }

    @Test
    fun `final status never changes`() = runTest {
        val journal = InMemoryJournal(sale("A", ProcessingStatus.CAPTURED))
        terminal.statusAnswer = ProcessingStatus.REFUNDED

        refresh(journal)(SaleReference("A"))

        assertEquals(ProcessingStatus.CAPTURED, journal.status("A"))
    }

    @Test
    fun `in progress does not go back to stored`() = runTest {
        val journal = InMemoryJournal(sale("A", IN_PROGRESS))
        terminal.statusAnswer = STORED

        refresh(journal)(SaleReference("A"))

        assertEquals(IN_PROGRESS, journal.status("A"))
    }

    @Test
    fun `stored is not moved to in progress or not found`() = runTest {
        for (answer in listOf(IN_PROGRESS, ProcessingStatus.NOT_FOUND)) {
            val journal = InMemoryJournal(sale("A", STORED))
            terminal.statusAnswer = answer

            val status = refresh(journal)(SaleReference("A"))

            assertEquals(answer, status)
            assertEquals(STORED, journal.status("A"))
        }
    }

    @Test
    fun `stored moves to a final status`() = runTest {
        val journal = InMemoryJournal(sale("A", STORED))
        terminal.statusAnswer = SUCCEEDED

        refresh(journal)(SaleReference("A"))

        assertEquals(SUCCEEDED, journal.status("A"))
    }

    @Test
    fun `not found is not moved to stored`() = runTest {
        val journal = InMemoryJournal(sale("A", ProcessingStatus.NOT_FOUND))
        terminal.statusAnswer = STORED

        refresh(journal)(SaleReference("A"))

        assertEquals(ProcessingStatus.NOT_FOUND, journal.status("A"))
    }

    @Test
    fun `no answer leaves the journal unchanged`() = runTest {
        val journal = InMemoryJournal(sale("A", IN_PROGRESS))
        terminal.statusAnswer = null

        val status = refresh(journal)(SaleReference("A"))

        assertNull(status)
        assertEquals(IN_PROGRESS, journal.status("A"))
        assertNull(journal.find(SaleReference("A"))?.statusCheckedAt)
    }

    @Test
    fun `sale missing from the journal returns the status and writes nothing`() = runTest {
        val journal = InMemoryJournal()
        terminal.statusAnswer = SUCCEEDED

        val status = refresh(journal)(SaleReference("X"))

        assertEquals(SUCCEEDED, status)
        assertNull(journal.find(SaleReference("X")))
    }
}
