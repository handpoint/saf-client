package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.STORED
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeleteStoredSaleTest {
    private val terminal = FakeTerminal()

    @Test
    fun `deleted on the terminal removes the stored sale`() = runTest {
        val journal = InMemoryJournal(sale("A", STORED))
        terminal.deleteResult = DeleteResult.DELETED

        val result = DeleteStoredSale(terminal, journal)(SaleReference("A"))

        assertEquals(DeleteResult.DELETED, result)
        assertNull(journal.find(SaleReference("A")))
    }

    @Test
    fun `not on the terminal removes the stored sale`() = runTest {
        val journal = InMemoryJournal(sale("A", STORED))
        terminal.deleteResult = DeleteResult.NOT_ON_TERMINAL

        DeleteStoredSale(terminal, journal)(SaleReference("A"))

        assertNull(journal.find(SaleReference("A")))
    }

    @Test
    fun `sale already sent is kept`() = runTest {
        val journal = InMemoryJournal(sale("A", IN_PROGRESS))
        terminal.deleteResult = DeleteResult.NOT_ON_TERMINAL

        DeleteStoredSale(terminal, journal)(SaleReference("A"))

        assertEquals(IN_PROGRESS, journal.status("A"))
    }

    @Test
    fun `error keeps the sale`() = runTest {
        val journal = InMemoryJournal(sale("A", STORED))
        terminal.deleteResult = DeleteResult.ERROR

        val result = DeleteStoredSale(terminal, journal)(SaleReference("A"))

        assertEquals(DeleteResult.ERROR, result)
        assertEquals(STORED, journal.status("A"))
    }
}
