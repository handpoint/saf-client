package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.ProcessingStatus.FAILED
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.NOT_FOUND
import com.handpoint.safclient.domain.model.ProcessingStatus.STORED
import com.handpoint.safclient.domain.model.ProcessingStatus.SUCCEEDED
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.testutil.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshOpenStatusesTest {

    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val clock = MutableClock(now)
    private val terminal = FakeTerminal()

    private fun sent(reference: String, status: ProcessingStatus, ago: Duration): Sale =
        sale(reference, status).copy(sentAt = now - ago)

    /** The app scope runs on the test scheduler but outlives the test body, like the real one. */
    private fun TestScope.statuses(journal: InMemoryJournal) = RefreshOpenStatuses(
        journal = journal,
        refresh = RefreshSaleStatus(terminal, journal, clock),
        clock = clock,
        appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
    )

    private fun requested() = terminal.statusRequests.map(SaleReference::value)

    @Test
    fun `a pass asks only open sales sent in the last day, newest first`() = runTest {
        terminal.statusAnswer = IN_PROGRESS
        val journal = InMemoryJournal(
            sent("old", IN_PROGRESS, Duration.ofHours(30)),
            sent("recent", IN_PROGRESS, Duration.ofMinutes(5)),
            sent("unknown", NOT_FOUND, Duration.ofHours(2)),
            sent("paid", SUCCEEDED, Duration.ofMinutes(1)),
            sent("failed", FAILED, Duration.ofMinutes(1)),
            sale("stored", STORED),
        )

        val asked = statuses(journal)()

        assertEquals(listOf("recent", "unknown"), requested())
        assertEquals(2, asked)
    }

    @Test
    fun `a pass asks at most twenty sales`() = runTest {
        terminal.statusAnswer = IN_PROGRESS
        val sales = (1..25).map { sent("s$it", IN_PROGRESS, Duration.ofMinutes(it.toLong())) }
        val journal = InMemoryJournal(*sales.toTypedArray())

        statuses(journal)()

        assertEquals(RefreshOpenStatuses.MAX_PER_PASS, terminal.statusRequests.size)
        assertEquals("s1", requested().first())
    }

    @Test
    fun `a pass stops at the first sale with no answer`() = runTest {
        terminal.statusAnswer = null // no network: the next sales would fail too
        val journal = InMemoryJournal(
            sent("a", IN_PROGRESS, Duration.ofMinutes(1)),
            sent("b", IN_PROGRESS, Duration.ofMinutes(2)),
        )

        statuses(journal)()

        assertEquals(listOf("a"), requested())
    }

    @Test
    fun `follow-up asks the sent sales at 10 s, 30 s and 2 min while they stay open`() = runTest {
        terminal.statusAnswer = IN_PROGRESS
        val journal = InMemoryJournal(sent("a", IN_PROGRESS, Duration.ZERO))

        statuses(journal).followUp(listOf(SaleReference("a")))

        advanceTimeBy(9_999); runCurrent()
        assertEquals(0, terminal.statusRequests.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, terminal.statusRequests.size)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, terminal.statusRequests.size)
        advanceTimeBy(90_000); runCurrent()
        assertEquals(3, terminal.statusRequests.size)
        advanceUntilIdle()
        assertEquals(3, terminal.statusRequests.size)
    }

    @Test
    fun `follow-up stops once every sent sale has a final status`() = runTest {
        terminal.statusAnswers[SaleReference("a")] = SUCCEEDED
        terminal.statusAnswers[SaleReference("b")] = IN_PROGRESS
        val journal = InMemoryJournal(
            sent("a", IN_PROGRESS, Duration.ZERO),
            sent("b", IN_PROGRESS, Duration.ZERO),
        )

        statuses(journal).followUp(listOf(SaleReference("a"), SaleReference("b")))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(setOf("a", "b"), requested().toSet())

        terminal.statusAnswers[SaleReference("b")] = SUCCEEDED
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf("a", "b", "b"), requested())

        advanceUntilIdle()
        assertEquals(3, terminal.statusRequests.size)
        assertEquals(SUCCEEDED, journal.status("a"))
        assertEquals(SUCCEEDED, journal.status("b"))
    }

    @Test
    fun `it is active from the follow-up call until the follow-up ends`() = runTest {
        terminal.statusAnswer = IN_PROGRESS
        val journal = InMemoryJournal(sent("a", IN_PROGRESS, Duration.ZERO))
        val statuses = statuses(journal)
        assertEquals(false, statuses.isActive.value)

        statuses.followUp(listOf(SaleReference("a")))
        assertEquals(true, statuses.isActive.value) // at once, before the follow-up job runs

        advanceTimeBy(119_000); runCurrent()
        assertEquals(true, statuses.isActive.value)
        advanceUntilIdle()
        assertEquals(false, statuses.isActive.value)
    }

    @Test
    fun `a replaced follow-up keeps it active until the new one ends`() = runTest {
        terminal.statusAnswer = SUCCEEDED
        val journal = InMemoryJournal(sent("a", IN_PROGRESS, Duration.ZERO), sent("b", IN_PROGRESS, Duration.ZERO))
        val statuses = statuses(journal)

        statuses.followUp(listOf(SaleReference("a")))
        statuses.followUp(listOf(SaleReference("b")))
        runCurrent()
        assertEquals(true, statuses.isActive.value)

        advanceUntilIdle()
        assertEquals(false, statuses.isActive.value)
    }

    @Test
    fun `it is active while a pass runs`() = runTest {
        terminal.statusAnswer = IN_PROGRESS
        val gate = CompletableDeferred<Unit>()
        terminal.statusGate = gate
        val journal = InMemoryJournal(sent("a", IN_PROGRESS, Duration.ZERO))
        val statuses = statuses(journal)

        val pass = launch { statuses() }
        runCurrent()
        assertEquals(true, statuses.isActive.value)

        gate.complete(Unit)
        pass.join()
        assertEquals(false, statuses.isActive.value)
    }
}
