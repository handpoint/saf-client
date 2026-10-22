package com.handpoint.safclient.ui.home

import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus.FAILED
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.NOT_FOUND
import com.handpoint.safclient.domain.model.ProcessingStatus.STORED
import com.handpoint.safclient.domain.model.ProcessingStatus.SUCCEEDED
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.RefreshSaleStatus
import com.handpoint.safclient.domain.usecase.SendStoredSales
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.RecordingErrorReporter
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = MutableClock(Instant.parse("2026-10-22T12:00:00Z"))
    private val terminal = FakeTerminal()
    private val settings = FakePreferences()
    private val errors = RecordingErrorReporter()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.homeViewModel(
        journal: InMemoryJournal,
        appScope: CoroutineScope = backgroundScope,
    ): Pair<HomeViewModel, SendStoredSales> {
        val reconcile = ReconcileStoredSales(terminal, journal, settings, clock)
        val refresh = RefreshSaleStatus(terminal, journal, clock)
        val send = SendStoredSales(terminal, journal, reconcile, clock, appScope)
        val viewModel = HomeViewModel(journal, terminal, settings, reconcile, refresh, send, clock, dispatcher, errors)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        return viewModel to send
    }

    @Test
    fun `pending total and count come from STORED sales only`() = runTest(dispatcher) {
        terminal.storedSummary = null // keep the journal as it is
        val journal = InMemoryJournal(
            sale("A", STORED, minorUnits = 1250),
            sale("B", STORED, minorUnits = 4700),
            sale("C", SUCCEEDED, minorUnits = 820),
            sale("D", IN_PROGRESS, minorUnits = 300),
        )
        val (viewModel, _) = homeViewModel(journal)

        val state = viewModel.uiState.value
        assertEquals(Money(5950, USD), state.pending)
        assertEquals(2, state.pendingCount)
        assertEquals(listOf("A", "B"), state.stored.map { it.reference.value }.sorted())
    }

    @Test
    fun `empty journal has nothing pending`() = runTest(dispatcher) {
        val (viewModel, _) = homeViewModel(InMemoryJournal())

        val state = viewModel.uiState.value
        assertEquals(0, state.pendingCount)
        assertNull(state.pending)
        assertTrue(state.today.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `connects once on init when the terminal is not connected`() = runTest(dispatcher) {
        terminal.connectionState.value = TerminalConnection.DISCONNECTED
        val (viewModel, _) = homeViewModel(InMemoryJournal())

        viewModel.onResume()
        clock.advance(Duration.ofMinutes(5))
        viewModel.onResume()

        assertEquals(1, terminal.connectCalls)
        assertEquals(TerminalConnection.CONNECTED, viewModel.uiState.value.connection)
    }

    @Test
    fun `does not connect when the terminal is already connected`() = runTest(dispatcher) {
        homeViewModel(InMemoryJournal())

        assertEquals(0, terminal.connectCalls)
    }

    @Test
    fun `reconciles on init and on resume at most every 30 seconds`() = runTest(dispatcher) {
        val (viewModel, _) = homeViewModel(InMemoryJournal())
        assertEquals(1, terminal.storedCalls)

        viewModel.onResume() // the first resume follows init straight away
        clock.advance(Duration.ofSeconds(29))
        viewModel.onResume()
        assertEquals(1, terminal.storedCalls)

        clock.advance(Duration.ofSeconds(1))
        viewModel.onResume()
        assertEquals(2, terminal.storedCalls)
    }

    @Test
    fun `the 30 second throttle starts when the opening reconcile ends`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        terminal.storedGate = gate
        val (viewModel, _) = homeViewModel(InMemoryJournal())

        clock.advance(Duration.ofSeconds(40))
        viewModel.onResume() // the opening reconcile is still running
        assertEquals(1, terminal.storedCalls)

        gate.complete(Unit) // the opening reconcile ends now
        clock.advance(Duration.ofSeconds(29))
        viewModel.onResume()
        assertEquals(1, terminal.storedCalls)

        clock.advance(Duration.ofSeconds(1))
        viewModel.onResume()
        assertEquals(2, terminal.storedCalls)
    }

    @Test
    fun `a failing connect is reported and Home still reconciles`() = runTest(dispatcher) {
        terminal.connectionState.value = TerminalConnection.DISCONNECTED
        val failure = IllegalStateException("SDK cannot be created")
        terminal.connectError = failure
        val (viewModel, _) = homeViewModel(InMemoryJournal())

        assertEquals(1, terminal.storedCalls)
        assertEquals(TerminalConnection.DISCONNECTED, viewModel.uiState.value.connection)
        assertFalse(viewModel.uiState.value.canTakeSale)
        assertEquals(listOf("connect" to failure.message), errors.summary())
    }

    @Test
    fun `a failing reconcile is reported`() = runTest(dispatcher) {
        val failure = IllegalStateException("terminal busy")
        terminal.storedError = failure
        val (viewModel, _) = homeViewModel(InMemoryJournal(sale("A", STORED)))

        assertEquals(listOf("reconcile" to failure.message), errors.summary())
        assertEquals(1, viewModel.uiState.value.pendingCount)
    }

    @Test
    fun `retrying the connection connects again and reconciles`() = runTest(dispatcher) {
        terminal.connectionState.value = TerminalConnection.DISCONNECTED
        terminal.connectError = IllegalStateException("SDK cannot be created")
        val (viewModel, _) = homeViewModel(InMemoryJournal())

        terminal.connectError = null
        viewModel.retryConnection()

        assertEquals(2, terminal.connectCalls)
        assertEquals(2, terminal.storedCalls)
        assertEquals(TerminalConnection.CONNECTED, viewModel.uiState.value.connection)

        viewModel.retryConnection() // already connected: nothing to do
        assertEquals(2, terminal.connectCalls)
    }

    @Test
    fun `reconcile on init marks sales that left the terminal`() = runTest(dispatcher) {
        terminal.storedSummary = StoredSummary(emptyList(), null)
        val journal = InMemoryJournal(sale("A", STORED))
        val (viewModel, _) = homeViewModel(journal)

        assertEquals(IN_PROGRESS, journal.status("A"))
        assertEquals(0, viewModel.uiState.value.pendingCount)
    }

    @Test
    fun `today lists only sales approved today, newest first`() = runTest(dispatcher) {
        terminal.storedSummary = null
        val journal = InMemoryJournal(
            sale("morning", SUCCEEDED).copy(approvedAt = Instant.parse("2026-10-22T08:30:00Z")),
            sale("noon", STORED).copy(approvedAt = Instant.parse("2026-10-22T11:59:00Z")),
            sale("yesterday", STORED).copy(approvedAt = Instant.parse("2026-10-21T23:59:00Z")),
        )
        val (viewModel, _) = homeViewModel(journal)

        val state = viewModel.uiState.value
        assertEquals(listOf("noon", "morning"), state.today.map { it.reference.value })
        assertEquals(2, state.pendingCount) // a stored sale from yesterday still waits to be sent
    }

    @Test
    fun `refreshes status once for today's IN_PROGRESS and NOT_FOUND sales`() = runTest(dispatcher) {
        terminal.storedSummary = null
        terminal.statusAnswer = SUCCEEDED
        val journal = InMemoryJournal(
            sale("sent", IN_PROGRESS),
            sale("unknown", NOT_FOUND),
            sale("stored", STORED),
            sale("failed", FAILED),
            sale("old", IN_PROGRESS).copy(approvedAt = Instant.parse("2026-10-21T10:00:00Z")),
        )
        val (viewModel, _) = homeViewModel(journal)
        clock.advance(Duration.ofMinutes(1))
        viewModel.onResume()

        assertEquals(setOf("sent", "unknown"), terminal.statusRequests.map(SaleReference::value).toSet())
        assertEquals(2, terminal.statusRequests.size)
        assertEquals(SUCCEEDED, journal.status("sent"))
    }

    @Test
    fun `shows merchant name and whether a forwarding run is active`() = runTest(dispatcher) {
        settings.merchantName.value = "Café Aurora"
        terminal.storedSummary = null
        val (viewModel, send) = homeViewModel(InMemoryJournal(sale("A", STORED)))
        assertFalse(viewModel.uiState.value.isSending)

        send() // the fake terminal accepts and then stays silent, so the run stays active

        assertEquals("Café Aurora", viewModel.uiState.value.merchantName)
        assertTrue(viewModel.uiState.value.isSending)
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(by: Duration) {
            now += by
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
