package com.handpoint.safclient.ui.sending

import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.ForwardingEvent.Finished
import com.handpoint.safclient.domain.model.ForwardingEvent.Sent
import com.handpoint.safclient.domain.model.ForwardingEvent.Started
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.SendStoredSales
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.testutil.RecordingErrorReporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SendingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val terminal = FakeTerminal()
    private val journal = InMemoryJournal(
        sale("A", minorUnits = 1250),
        sale("B", minorUnits = 4700),
        sale("C", minorUnits = 2500),
    )
    private val errors = RecordingErrorReporter()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Keep the journal as it is after each run.
        terminal.storedSummary = null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The app scope outlives the screen, as in the app: it is not a child of the test. */
    private fun TestScope.sendStoredSales() = SendStoredSales(
        terminal = terminal,
        journal = journal,
        reconcile = ReconcileStoredSales(terminal, journal, FakePreferences(), clock),
        clock = clock,
        appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
    )

    private fun TestScope.sendingViewModel(send: SendStoredSales = sendStoredSales()) =
        SendingViewModel(send, journal, errors)

    private fun ref(value: String) = SaleReference(value)

    @Test
    fun `starts a run on init and shows progress`() = runTest(dispatcher) {
        val viewModel = sendingViewModel()
        assertEquals(SendingUiState.Starting, viewModel.state.value)
        runCurrent()

        terminal.emitForwarding(Started(3))
        terminal.emitForwarding(Sent(ref("A"), 1, 3))
        runCurrent()

        assertEquals(1, terminal.forwardCalls)
        assertEquals(SendingUiState.Running(sent = 1, total = 3), viewModel.state.value)
        assertEquals(
            mapOf("A" to SendingRowStatus.SENT, "B" to SendingRowStatus.WAITING, "C" to SendingRowStatus.WAITING),
            viewModel.rows.value.associate { it.reference.value to it.status },
        )
        terminal.emitForwarding(Finished(ForwardingResult.AllSent(3)))
        advanceUntilIdle()
    }

    @Test
    fun `network stop halfway ends in Done and marks what is still on the terminal`() = runTest(dispatcher) {
        val partial = ForwardingResult.Partial(1, 0, 2, 3, ForwardingError.NETWORK)
        terminal.forwardScript = listOf(Started(3), Sent(ref("A"), 1, 3), Finished(partial))
        val viewModel = sendingViewModel()

        advanceUntilIdle()

        assertEquals(SendingUiState.Done(partial), viewModel.state.value)
        assertEquals(
            listOf(
                SendingRow(ref("A"), Money(1250, USD), SendingRowStatus.SENT),
                SendingRow(ref("B"), Money(4700, USD), SendingRowStatus.STILL_ON_TERMINAL),
                SendingRow(ref("C"), Money(2500, USD), SendingRowStatus.STILL_ON_TERMINAL),
            ).sortedBy { it.reference.value },
            viewModel.rows.value.sortedBy { it.reference.value },
        )
        assertTrue(partial.canTryAgain())
    }

    @Test
    fun `retry starts a new run`() = runTest(dispatcher) {
        val partial = ForwardingResult.Partial(1, 0, 2, 3, ForwardingError.NETWORK)
        terminal.forwardScript = listOf(Started(3), Sent(ref("A"), 1, 3), Finished(partial))
        val viewModel = sendingViewModel()
        advanceUntilIdle()

        val allSent = ForwardingResult.AllSent(2)
        terminal.forwardScript = listOf(Started(2), Sent(ref("B"), 1, 2), Sent(ref("C"), 2, 2), Finished(allSent))
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, terminal.forwardCalls)
        assertEquals(SendingUiState.Done(allSent), viewModel.state.value)
        assertEquals(listOf("B", "C"), viewModel.rows.value.map { it.reference.value }.sorted())
        assertTrue(viewModel.rows.value.all { it.status == SendingRowStatus.SENT })
    }

    @Test
    fun `retry waits for the previous run to finish its journal updates`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        terminal.storedGate = gate
        val noConnection = ForwardingResult.NoneSent(ForwardingError.NETWORK, 0, 3, 3)
        terminal.forwardScript = listOf(Started(3), Finished(noConnection))
        val viewModel = sendingViewModel()
        advanceUntilIdle()
        assertEquals(SendingUiState.Done(noConnection), viewModel.state.value)
        assertTrue(viewModel.isRunning.value) // reconcile held open

        val allSent = ForwardingResult.AllSent(3)
        terminal.forwardScript = listOf(Started(3), Finished(allSent))
        viewModel.retry()
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(1, terminal.forwardCalls)
        assertEquals(SendingUiState.Done(noConnection), viewModel.state.value)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, terminal.forwardCalls)
        assertEquals(SendingUiState.Done(allSent), viewModel.state.value)
    }

    @Test
    fun `rows of a joined run stay oldest first`() = runTest(dispatcher) {
        journal.record(sale("A").copy(approvedAt = Instant.parse("2026-10-22T09:00:00Z")))
        journal.record(sale("B").copy(approvedAt = Instant.parse("2026-10-22T10:00:00Z")))
        journal.record(sale("C").copy(approvedAt = Instant.parse("2026-10-22T11:00:00Z")))
        val send = sendStoredSales()
        send() // a run started from elsewhere before the screen opened
        runCurrent()
        terminal.emitForwarding(Started(3))
        terminal.emitForwarding(Sent(ref("B"), 1, 3))
        runCurrent()

        val viewModel = sendingViewModel(send)
        runCurrent()

        assertEquals(listOf("A", "B", "C"), viewModel.rows.value.map { it.reference.value })
        assertEquals(SendingRowStatus.SENT, viewModel.rows.value[1].status)
        terminal.emitForwarding(Finished(ForwardingResult.AllSent(3)))
        advanceUntilIdle()
    }

    @Test
    fun `retry is ignored while a run is active`() = runTest(dispatcher) {
        val viewModel = sendingViewModel()
        runCurrent()

        viewModel.retry()
        runCurrent()

        assertEquals(1, terminal.forwardCalls)
        terminal.emitForwarding(Finished(ForwardingResult.AllSent(3)))
        advanceUntilIdle()
    }

    @Test
    fun `nothing to send ends in Done with no result`() = runTest(dispatcher) {
        terminal.forwardScript = listOf(ForwardingEvent.NothingToSend)
        val viewModel = sendingViewModel()

        advanceUntilIdle()

        assertEquals(SendingUiState.Done(null), viewModel.state.value)
    }

    @Test
    fun `try again is offered only after a network stop`() {
        assertTrue(ForwardingResult.NoneSent(ForwardingError.NETWORK, 0, 3, 3).canTryAgain())
        assertFalse(ForwardingResult.NoneSent(ForwardingError.FAILED, 0, 0, 0).canTryAgain())
        assertFalse(ForwardingResult.Partial(1, 1, 0, 2, ForwardingError.CORRUPTED).canTryAgain())
        assertFalse(ForwardingResult.AllSent(3).canTryAgain())
        assertFalse(null.canTryAgain())
    }
}
