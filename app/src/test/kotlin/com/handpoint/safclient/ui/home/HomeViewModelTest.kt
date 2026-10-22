package com.handpoint.safclient.ui.home

import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.domain.model.ProcessingStatus.FAILED
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.NOT_FOUND
import com.handpoint.safclient.domain.model.ProcessingStatus.STORED
import com.handpoint.safclient.domain.model.ProcessingStatus.SUCCEEDED
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.RefreshOpenStatuses
import com.handpoint.safclient.domain.usecase.RefreshSaleStatus
import com.handpoint.safclient.domain.usecase.SendStoredSales
import com.handpoint.safclient.testutil.FakeNetworkMonitor
import com.handpoint.safclient.testutil.FakeOnlineTerminal
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.testutil.MutableClock
import com.handpoint.safclient.testutil.RecordingErrorReporter
import com.handpoint.safclient.testutil.paymentMode
import java.time.Duration
import java.time.Instant
import java.util.Currency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = MutableClock(Instant.parse("2026-10-22T12:00:00Z"))
    private val terminal = FakeTerminal()
    private val settings = FakePreferences()
    private val errors = RecordingErrorReporter()
    private val online = FakeOnlineTerminal()
    private val network = FakeNetworkMonitor(NetworkStatus.OFFLINE)
    private lateinit var payments: PaymentModeCoordinator

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.homeViewModel(
        journal: InMemoryJournal,
        appScope: CoroutineScope = backgroundScope,
    ): Pair<HomeViewModel, SendStoredSales> {
        val reconcile = ReconcileStoredSales(terminal, journal, settings, clock)
        val statuses = RefreshOpenStatuses(journal, RefreshSaleStatus(terminal, journal, clock), clock, appScope)
        val send = SendStoredSales(terminal, journal, reconcile, statuses, clock, appScope)
        payments = paymentMode(
            saf = terminal,
            journal = journal,
            clock = clock,
            online = online,
            network = network,
            reconcile = reconcile,
            statuses = statuses,
            send = send,
            errors = errors,
            io = dispatcher,
        )
        payments.start() // SafClientApp does this at app start
        val viewModel = HomeViewModel(journal, payments, settings, reconcile, statuses, send, clock, dispatcher, errors)
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
    fun `sales only the terminal knows count as waiting`() = runTest(dispatcher) {
        terminal.storedSummary = StoredSummary(listOf(SaleReference("X"), SaleReference("Y")), Money(3000, USD))

        val (viewModel, _) = homeViewModel(InMemoryJournal())

        val state = viewModel.uiState.value
        assertEquals(2, state.pendingCount)
        assertEquals(Money(3000, USD), state.pending)
        assertTrue(state.canSend)
        assertEquals(2, state.notInList)
    }

    @Test
    fun `terminal sales missing from the journal are counted`() = runTest(dispatcher) {
        terminal.storedSummary = StoredSummary(listOf("A", "X", "Y").map(::SaleReference), Money(3500, USD))

        val (viewModel, _) = homeViewModel(InMemoryJournal(sale("A", STORED, minorUnits = 500)))

        val state = viewModel.uiState.value
        assertEquals(3, state.pendingCount)
        assertEquals(Money(3500, USD), state.pending)
        assertEquals(listOf("A"), state.stored.map { it.reference.value })
        assertEquals(2, state.notInList)
    }

    @Test
    fun `the journal counts when it holds more than the last terminal answer`() = runTest(dispatcher) {
        terminal.storedSummary = StoredSummary(listOf(SaleReference("A")), Money(500, USD))
        val journal = InMemoryJournal(sale("A", STORED, minorUnits = 500))
        val (viewModel, _) = homeViewModel(journal)

        journal.record(sale("B", STORED, minorUnits = 700).copy(approvedAt = clock.instant()))

        val state = viewModel.uiState.value
        assertEquals(2, state.pendingCount)
        assertEquals(Money(1200, USD), state.pending)
        assertEquals(0, state.notInList)
    }

    @Test
    fun `the terminal currency wins when the totals differ in currency`() = runTest(dispatcher) {
        val eur = Currency.getInstance("EUR")
        terminal.storedSummary = StoredSummary(listOf(SaleReference("X")), Money(900, eur))
        val journal = InMemoryJournal()
        val (viewModel, _) = homeViewModel(journal)
        journal.record(sale("A", STORED).copy(approvedAt = clock.instant()))
        journal.record(sale("B", STORED).copy(approvedAt = clock.instant()))

        val state = viewModel.uiState.value
        assertEquals(Money(900, eur), state.pending)
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun `send now needs a connected terminal`() = runTest(dispatcher) {
        terminal.storedSummary = StoredSummary(listOf(SaleReference("A")), Money(1000, USD))
        val (viewModel, _) = homeViewModel(InMemoryJournal(sale("A", STORED)))
        assertTrue(viewModel.uiState.value.canSend)

        terminal.connectionState.value = TerminalConnection.DISCONNECTED

        assertFalse(viewModel.uiState.value.canSend)
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
    fun `a failing connect is reported and Home waits for the terminal before reconciling`() = runTest(dispatcher) {
        terminal.connectionState.value = TerminalConnection.DISCONNECTED
        val failure = IllegalStateException("SDK cannot be created")
        terminal.connectError = failure
        val (viewModel, _) = homeViewModel(InMemoryJournal())

        assertEquals(0, terminal.storedCalls)
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
        assertEquals(1, terminal.storedCalls)
        assertEquals(TerminalConnection.CONNECTED, viewModel.uiState.value.connection)

        viewModel.retryConnection() // already connected: nothing to do
        assertEquals(2, terminal.connectCalls)
    }

    @Test
    fun `Home reconciles when the terminal becomes connected, not before`() = runTest(dispatcher) {
        // connect() returns while the SDK is still setting up; it reports CONNECTED later.
        terminal.connectionState.value = TerminalConnection.CONNECTING
        terminal.connectError = IllegalStateException("still setting up")
        homeViewModel(InMemoryJournal())
        assertEquals(0, terminal.storedCalls)

        terminal.connectionState.value = TerminalConnection.CONNECTED
        runCurrent()

        assertEquals(1, terminal.storedCalls)
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
    fun `coming back asks about open sales again at most every five minutes`() = runTest(dispatcher) {
        terminal.storedSummary = null
        terminal.statusAnswer = IN_PROGRESS
        val (viewModel, _) = homeViewModel(InMemoryJournal(sale("sent", IN_PROGRESS)))
        assertEquals(1, terminal.statusRequests.size) // the opening pass

        clock.advance(Duration.ofMinutes(4))
        viewModel.onResume()
        runCurrent()
        assertEquals(1, terminal.statusRequests.size)

        clock.advance(Duration.ofMinutes(1))
        viewModel.onResume()
        runCurrent()
        assertEquals(2, terminal.statusRequests.size)
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

    // --- online and offline modes ----------------------------------------------------------------

    @Test
    fun `with network and nothing stored, Home goes online and stops reconciling and asking`() = runTest(dispatcher) {
        terminal.storedSummary = StoredSummary(emptyList(), null)
        network.status.value = NetworkStatus.ONLINE
        val journal = InMemoryJournal(sale("A", IN_PROGRESS).copy(sentAt = clock.instant()))
        val (viewModel, _) = homeViewModel(journal)
        runCurrent()
        assertEquals(PaymentMode.ONLINE, viewModel.uiState.value.mode.mode)
        val storedCalls = terminal.storedCalls
        val statusCalls = terminal.statusRequests.size

        clock.advance(Duration.ofMinutes(10))
        viewModel.onResume()
        runCurrent()

        assertEquals(storedCalls, terminal.storedCalls)
        assertEquals(statusCalls, terminal.statusRequests.size)
        assertTrue(viewModel.uiState.value.canTakeSale)
        assertFalse(viewModel.uiState.value.canSend)
    }

    @Test
    fun `New sale and Send now are disabled while the mode switches`() = runTest(dispatcher) {
        terminal.storedSummary = StoredSummary(listOf(SaleReference("X")), Money(500, USD))
        online.connectsOnActivate = false
        val (viewModel, _) = homeViewModel(InMemoryJournal())
        runCurrent()
        assertTrue(viewModel.uiState.value.canSend)

        // The sale leaves the terminal and the network comes back: the app switches to online.
        terminal.storedSummary = StoredSummary(emptyList(), null)
        clock.advance(Duration.ofMinutes(1))
        viewModel.onResume()
        network.status.value = NetworkStatus.ONLINE
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(PaymentMode.ONLINE, state.mode.switchingTo)
        assertFalse(state.canTakeSale)
        assertFalse(state.canSend)
    }

    @Test
    fun `Home reconciles again when the app comes back to store and forward`() = runTest(dispatcher) {
        terminal.storedSummary = StoredSummary(emptyList(), null)
        network.status.value = NetworkStatus.ONLINE
        val (viewModel, _) = homeViewModel(InMemoryJournal())
        runCurrent()
        assertEquals(PaymentMode.ONLINE, viewModel.uiState.value.mode.mode)
        val storedCalls = terminal.storedCalls

        network.status.value = NetworkStatus.OFFLINE
        runCurrent()

        assertEquals(PaymentMode.OFFLINE, viewModel.uiState.value.mode.mode)
        assertEquals(storedCalls + 1, terminal.storedCalls)
    }
}
