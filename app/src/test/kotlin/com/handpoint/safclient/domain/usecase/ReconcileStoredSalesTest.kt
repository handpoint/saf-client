package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.NOT_FOUND
import com.handpoint.safclient.domain.model.ProcessingStatus.STORED
import com.handpoint.safclient.domain.model.ProcessingStatus.SUCCEEDED
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.testutil.MutableClock
import java.time.Duration
import java.time.Instant
import java.util.Currency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconcileStoredSalesTest {
    private val clock = MutableClock(Instant.parse("2026-10-22T12:00:00Z"))
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
    fun `returns the terminal summary and remembers its currency`() = runTest {
        val summary = StoredSummary(listOf(SaleReference("A")), Money(1000, USD))
        terminal.storedSummary = summary

        val result = reconcile()

        assertEquals(summary, result)
        assertEquals(summary, reconcile.lastSummary.value)
        assertEquals(USD, settings.safCurrency.value)
    }

    @Test
    fun `nothing is asked or changed until the terminal is connected`() = runTest {
        // Before the SDK finishes its set-up, its stored list carries the built-in default
        // currency (GBP), not the one configured in TMS.
        terminal.connectionState.value = TerminalConnection.CONNECTING
        terminal.storedSummary = StoredSummary(emptyList(), Money(0, Currency.getInstance("GBP")))

        val result = reconcile()

        assertNull(result)
        assertEquals(0, terminal.storedCalls)
        assertNull(reconcile.lastSummary.value)
        assertEquals(Currency.getInstance("EUR"), settings.safCurrency.value)
        assertEquals(listOf(STORED, STORED, STORED), listOf("A", "B", "C").map { journal.status(it) })
    }

    @Test
    fun `no answer from the terminal changes nothing and keeps the last summary`() = runTest {
        val summary = StoredSummary(listOf(SaleReference("A"), SaleReference("B"), SaleReference("C")), Money(3000, USD))
        terminal.storedSummary = summary
        reconcile()
        terminal.storedSummary = null

        val result = reconcile()

        assertNull(result)
        assertEquals(summary, reconcile.lastSummary.value)
        assertEquals(listOf(STORED, STORED, STORED), listOf("A", "B", "C").map { journal.status(it) })
    }

    @Test
    fun `empty terminal with no total keeps the currency`() = runTest {
        terminal.storedSummary = StoredSummary(emptyList(), null)

        reconcile()

        assertEquals(Currency.getInstance("EUR"), settings.safCurrency.value)
        assertEquals(listOf(IN_PROGRESS, IN_PROGRESS, IN_PROGRESS), listOf("A", "B", "C").map { journal.status(it) })
    }

    @Test
    fun `a sale recorded while the terminal answers stays stored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        terminal.storedGate = gate
        terminal.storedSummary = StoredSummary(listOf(SaleReference("A"), SaleReference("B"), SaleReference("C")), Money(3000, USD))
        val job = launch { reconcile() }
        runCurrent() // the terminal snapshot is now being taken

        clock.advance(Duration.ofSeconds(1))
        journal.record(sale("D").copy(approvedAt = clock.instant()))
        gate.complete(Unit)
        job.join()

        assertEquals(STORED, journal.status("D"))
    }

    @Test
    fun `sales the terminal still holds go back to stored`() = runTest {
        val journal = InMemoryJournal(
            sale("A", IN_PROGRESS).copy(sentAt = Instant.parse("2026-10-22T11:00:00Z")),
            sale("B", NOT_FOUND),
            sale("C", SUCCEEDED),
        )
        terminal.storedSummary = StoredSummary(listOf("A", "B", "C").map(::SaleReference), Money(3000, USD))

        ReconcileStoredSales(terminal, journal, settings, clock)()

        assertEquals(STORED, journal.status("A"))
        assertNull(journal.find(SaleReference("A"))?.sentAt)
        assertEquals(STORED, journal.status("B"))
        assertEquals(SUCCEEDED, journal.status("C"))
    }

    @Test
    fun `a sale sent while the terminal answers is not put back to stored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        terminal.storedGate = gate
        terminal.storedSummary = StoredSummary(listOf("A", "B", "C").map(::SaleReference), Money(3000, USD))
        val job = launch { reconcile() }
        runCurrent()

        clock.advance(Duration.ofSeconds(1))
        journal.markSent(SaleReference("A"), clock.instant())
        gate.complete(Unit)
        job.join()

        assertEquals(IN_PROGRESS, journal.status("A"))
    }

    @Test
    fun `reconcile leaves an unconfirmed online sale alone, listed by the terminal or not`() = runTest {
        val unconfirmed = sale("U", ProcessingStatus.UNKNOWN).copy(channel = SaleChannel.ONLINE)
        val other = sale("V", ProcessingStatus.UNKNOWN).copy(channel = SaleChannel.ONLINE)
        journal.record(unconfirmed)
        journal.record(other)
        terminal.storedSummary = StoredSummary(listOf(SaleReference("U")), null)
        clock.advance(java.time.Duration.ofMinutes(1))

        reconcile()

        assertEquals(unconfirmed, journal.find(SaleReference("U")))
        assertEquals(other, journal.find(SaleReference("V")))
    }
}
