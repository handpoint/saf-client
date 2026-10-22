package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.ForwardingEvent.Finished
import com.handpoint.safclient.domain.model.ForwardingEvent.Sent
import com.handpoint.safclient.domain.model.ForwardingEvent.Started
import com.handpoint.safclient.domain.model.ForwardingResult
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SendStoredSalesTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val terminal = FakeTerminal()
    private val journal = InMemoryJournal(sale("A"), sale("B"), sale("C"))

    /**
     * The app scope runs on the test scheduler but is not a child of the test, like the real
     * app scope outlives screens. (backgroundScope would not be driven by advanceUntilIdle.)
     */
    private fun TestScope.sendStoredSales() = SendStoredSales(
        terminal = terminal,
        journal = journal,
        reconcile = ReconcileStoredSales(terminal, journal, FakePreferences(), clock),
        clock = clock,
        appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
    )

    private fun ref(value: String) = SaleReference(value)

    @Test
    fun `happy path relays every event and marks all sales sent`() = runTest {
        val allSent = Finished(ForwardingResult.AllSent(3))
        val script = listOf(Started(3), Sent(ref("A"), 1, 3), Sent(ref("B"), 2, 3), Sent(ref("C"), 3, 3), allSent)
        terminal.forwardScript = script
        terminal.storedSummary = StoredSummary(emptyList(), null)
        val send = sendStoredSales()

        val events = send().toList()
        advanceUntilIdle()

        assertEquals(script, events)
        assertEquals(listOf(IN_PROGRESS, IN_PROGRESS, IN_PROGRESS), listOf("A", "B", "C").map { journal.status(it) })
        assertEquals(clock.instant(), journal.find(ref("A"))?.sentAt)
        assertEquals(1, terminal.storedCalls)
        assertFalse(send.isRunning.value)
    }

    @Test
    fun `second invocation joins the active run without a second forward`() = runTest {
        terminal.storedSummary = StoredSummary(emptyList(), null)
        val send = sendStoredSales()
        val first = mutableListOf<ForwardingEvent>()
        val second = mutableListOf<ForwardingEvent>()

        launch { send().toList(first) }
        runCurrent()
        assertTrue(send.isRunning.value)
        terminal.emitForwarding(Started(1))
        runCurrent()
        launch { send().toList(second) }
        runCurrent()
        terminal.emitForwarding(Sent(ref("A"), 1, 1))
        val done = Finished(ForwardingResult.AllSent(1))
        terminal.emitForwarding(done)
        advanceUntilIdle()

        assertEquals(1, terminal.forwardCalls)
        assertEquals(listOf(Started(1), Sent(ref("A"), 1, 1), done), first)
        assertEquals(first, second)
        assertFalse(send.isRunning.value)
    }

    @Test
    fun `network stop after one of three leaves two stored`() = runTest {
        val partial = Finished(ForwardingResult.Partial(1, 0, 0, 3, ForwardingError.NETWORK))
        terminal.forwardScript = listOf(Started(3), Sent(ref("A"), 1, 3), partial)
        terminal.storedSummary = StoredSummary(listOf(ref("B"), ref("C")), Money(2000, USD))

        val events = sendStoredSales()().toList()
        advanceUntilIdle()

        assertEquals(partial, events.last())
        assertEquals(IN_PROGRESS, journal.status("A"))
        assertEquals(STORED, journal.status("B"))
        assertEquals(STORED, journal.status("C"))
    }

    @Test
    fun `refused forward finishes with none sent`() = runTest {
        terminal.forwardAccepted = false
        val send = sendStoredSales()

        val events = send().toList()
        advanceUntilIdle()

        assertEquals(listOf(Finished(ForwardingResult.NoneSent(ForwardingError.FAILED, 0, 0, 0))), events)
        assertEquals(listOf(STORED, STORED, STORED), listOf("A", "B", "C").map { journal.status(it) })
        assertFalse(send.isRunning.value)
    }

    @Test
    fun `nothing to send ends the run`() = runTest {
        terminal.forwardScript = listOf(ForwardingEvent.NothingToSend)
        terminal.storedSummary = StoredSummary(listOf(ref("A"), ref("B"), ref("C")), Money(3000, USD))
        val send = sendStoredSales()

        val events = send().toList()
        advanceUntilIdle()

        assertEquals(listOf(ForwardingEvent.NothingToSend), events)
        assertFalse(send.isRunning.value)
    }

    @Test
    fun `journal is updated even when the collector stops mid-run`() = runTest {
        terminal.storedSummary = StoredSummary(listOf(ref("C")), Money(1000, USD))
        val send = sendStoredSales()

        val collector = launch { send().collect {} }
        runCurrent()
        terminal.emitForwarding(Started(3))
        terminal.emitForwarding(Sent(ref("A"), 1, 3))
        runCurrent()
        collector.cancel()
        terminal.emitForwarding(Sent(ref("B"), 2, 3))
        terminal.emitForwarding(Finished(ForwardingResult.Partial(2, 0, 1, 3, ForwardingError.FAILED)))
        advanceUntilIdle()

        assertEquals(IN_PROGRESS, journal.status("A"))
        assertEquals(IN_PROGRESS, journal.status("B"))
        assertEquals(STORED, journal.status("C"))
        assertEquals(1, terminal.storedCalls)
        assertFalse(send.isRunning.value)
    }

    @Test
    fun `a new invocation after a run ends starts a new run`() = runTest {
        terminal.forwardScript = listOf(ForwardingEvent.NothingToSend)
        terminal.storedSummary = StoredSummary(emptyList(), null)
        val send = sendStoredSales()

        send().toList()
        advanceUntilIdle()
        send().toList()
        advanceUntilIdle()

        assertEquals(2, terminal.forwardCalls)
    }
}
