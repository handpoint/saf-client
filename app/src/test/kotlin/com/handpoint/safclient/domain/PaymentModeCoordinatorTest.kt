package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.ForwardingEvent.Finished
import com.handpoint.safclient.domain.model.ForwardingEvent.Sent
import com.handpoint.safclient.domain.model.ForwardingEvent.Started
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.OnlineWait
import com.handpoint.safclient.domain.model.PaymentMode.OFFLINE
import com.handpoint.safclient.domain.model.PaymentMode.ONLINE
import com.handpoint.safclient.domain.model.ProcessingStatus
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
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentModeCoordinatorTest {

    private val clock = MutableClock(Instant.parse("2026-10-22T12:00:00Z"))
    private val saf = FakeTerminal() // connected
    private val online = FakeOnlineTerminal()
    private val network = FakeNetworkMonitor(NetworkStatus.OFFLINE)
    private val journal = InMemoryJournal()
    private val errors = RecordingErrorReporter()

    private lateinit var reconcile: ReconcileStoredSales
    private lateinit var send: SendStoredSales

    private fun TestScope.coordinator(): PaymentModeCoordinator {
        reconcile = ReconcileStoredSales(saf, journal, FakePreferences(), clock)
        val statuses = RefreshOpenStatuses(journal, RefreshSaleStatus(saf, journal, clock), clock, backgroundScope)
        send = SendStoredSales(saf, journal, reconcile, statuses, clock, backgroundScope)
        return PaymentModeCoordinator(
            saf = saf,
            online = online,
            network = network,
            journal = journal,
            reconcile = reconcile,
            send = send,
            statuses = statuses,
            errors = errors,
            appScope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
        )
    }

    /** A coordinator that has seen the terminal's answer (empty unless set before). */
    private suspend fun TestScope.started(): PaymentModeCoordinator = coordinator().also {
        current = it
        reconcile()
        it.start()
        runCurrent()
    }

    @Test
    fun `no network keeps store and forward`() = runTest {
        val mode = started()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(0, online.activateCalls)
        assertEquals(0, saf.deactivateCalls)
    }

    @Test
    fun `network with nothing pending switches to online`() = runTest {
        network.status.value = NetworkStatus.ONLINE

        val mode = started()

        assertEquals(ONLINE, mode.state.value.mode)
        assertNull(mode.state.value.switchingTo)
        assertEquals(1, saf.deactivateCalls)
        assertEquals(1, online.activateCalls)
        assertEquals(TerminalConnection.CONNECTED, mode.connection.value)
    }

    @Test
    fun `it waits for the terminal's answer before going online`() = runTest {
        saf.connectionState.value = TerminalConnection.DISCONNECTED // the terminal cannot answer yet
        network.status.value = NetworkStatus.ONLINE
        val mode = coordinator()

        mode.start()
        runCurrent()
        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(OnlineWait.AWAITING_TERMINAL, mode.state.value.onlineWait)

        saf.connectionState.value = TerminalConnection.CONNECTED
        reconcile()
        runCurrent()
        assertEquals(ONLINE, mode.state.value.mode)
        assertNull(mode.state.value.onlineWait)
    }

    @Test
    fun `when the network comes back and the terminal never answered, it asks the terminal`() = runTest {
        val mode = coordinator()
        mode.start()
        runCurrent()
        assertEquals(0, saf.storedCalls)

        network.status.value = NetworkStatus.ONLINE
        runCurrent()

        assertEquals(1, saf.storedCalls)
        assertEquals(ONLINE, mode.state.value.mode)
    }

    @Test
    fun `network with sales stored in the journal stays on store and forward`() = runTest {
        journal.record(sale("A"))
        saf.storedSummary = StoredSummary(listOf(SaleReference("A")), Money(1000, USD))
        network.status.value = NetworkStatus.ONLINE

        val mode = started()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(1, mode.state.value.waitingToSend)
        assertEquals(0, online.activateCalls)
    }

    @Test
    fun `network with sales only the terminal holds stays on store and forward`() = runTest {
        saf.storedSummary = StoredSummary(listOf(SaleReference("X"), SaleReference("Y")), Money(2000, USD))
        network.status.value = NetworkStatus.ONLINE

        val mode = started()

        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(2, mode.state.value.waitingToSend)
    }

    @Test
    fun `after the send and its status follow-up it switches to online`() = runTest {
        journal.record(sale("A"))
        saf.storedSummary = StoredSummary(listOf(SaleReference("A")), Money(1000, USD))
        network.status.value = NetworkStatus.ONLINE
        val mode = started()
        assertEquals(OFFLINE, mode.state.value.mode)

        saf.forwardScript = listOf(Started(1), Sent(SaleReference("A"), 1, 1), Finished(ForwardingResult.AllSent(1)))
        saf.storedSummary = StoredSummary(emptyList(), null)
        saf.statusAnswer = ProcessingStatus.IN_PROGRESS // the follow-up asks until its last time
        send().toList()
        runCurrent()
        assertEquals(OFFLINE, mode.state.value.mode) // the follow-up still needs Store and Forward
        assertEquals(OnlineWait.BUSY_FOLLOW_UP, mode.state.value.onlineWait)

        advanceTimeBy(119_000)
        runCurrent()
        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(0, online.activateCalls)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(ONLINE, mode.state.value.mode)
    }

    @Test
    fun `it never switches during a sale and switches once the sale ends`() = runTest {
        val mode = started()
        assertEquals(OFFLINE, mode.beginSale())

        network.status.value = NetworkStatus.ONLINE
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(OnlineWait.WAITING_IDLE, mode.state.value.onlineWait)
        assertEquals(0, online.activateCalls)

        mode.endSale()
        runCurrent()
        assertEquals(ONLINE, mode.state.value.mode)
    }

    @Test
    fun `it never switches during a forwarding run and switches once it ends`() = runTest {
        journal.record(sale("A"))
        saf.storedSummary = StoredSummary(listOf(SaleReference("A")), Money(1000, USD))
        val mode = started()
        send() // the run starts and waits for the terminal's events
        runCurrent()

        // Meanwhile nothing is pending any more and the network is back.
        saf.storedSummary = StoredSummary(emptyList(), null)
        reconcile()
        network.status.value = NetworkStatus.ONLINE
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(send.isRunning.value)
        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(0, online.activateCalls)

        saf.emitForwarding(Finished(ForwardingResult.AllSent(0)))
        runCurrent()
        assertEquals(ONLINE, mode.state.value.mode)
    }

    @Test
    fun `no sale starts while it switches`() = runTest {
        online.connectsOnActivate = false
        network.status.value = NetworkStatus.ONLINE

        val mode = started()

        assertEquals(ONLINE, mode.state.value.switchingTo)
        assertNull(mode.beginSale())
        assertEquals(TerminalConnection.CONNECTING, mode.connection.value)
    }

    @Test
    fun `a switch that times out falls back to store and forward and tries again later`() = runTest {
        online.connectsOnActivate = false
        network.status.value = NetworkStatus.ONLINE
        val mode = started()

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(ONLINE, mode.state.value.switchingTo)

        advanceTimeBy(1)
        runCurrent()
        val state = mode.state.value
        assertEquals(OFFLINE, state.mode)
        assertNull(state.switchingTo)
        assertTrue(state.onlineUnavailable)
        assertEquals(1, saf.activateCalls)
        assertTrue(online.deactivateCalls >= 1)
        assertEquals(listOf("switch to online"), errors.summary().map { it.first })
        assertEquals(TerminalConnection.CONNECTED, mode.connection.value)

        online.connectsOnActivate = true
        advanceTimeBy(PaymentModeCoordinator.RETRY_ONLINE_DELAYS[0].inWholeMilliseconds)
        runCurrent()
        assertEquals(ONLINE, mode.state.value.mode)
        assertFalse(mode.state.value.onlineUnavailable)
    }

    @Test
    fun `an online API that cannot be created falls back at once`() = runTest {
        online.activateError = IllegalStateException("SDK cannot be created")
        network.status.value = NetworkStatus.ONLINE

        val mode = started()

        assertEquals(OFFLINE, mode.state.value.mode)
        assertTrue(mode.state.value.onlineUnavailable)
        assertEquals(listOf("switch to online" to "SDK cannot be created"), errors.summary())
    }

    @Test
    fun `losing the network while online switches back to store and forward`() = runTest {
        network.status.value = NetworkStatus.ONLINE
        val mode = started()
        assertEquals(ONLINE, mode.state.value.mode)

        network.status.value = NetworkStatus.OFFLINE
        runCurrent()

        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(1, online.deactivateCalls)
        assertEquals(1, saf.activateCalls)
        assertEquals(TerminalConnection.CONNECTED, mode.connection.value)
    }

    @Test
    fun `the state carries the network status`() = runTest {
        val mode = started()
        assertEquals(NetworkStatus.OFFLINE, mode.state.value.network)

        network.status.value = NetworkStatus.ONLINE
        runCurrent()

        assertEquals(NetworkStatus.ONLINE, mode.state.value.network)
    }

    @Test
    fun `connect, cancel and print go to the API in use`() = runTest {
        network.status.value = NetworkStatus.ONLINE
        val mode = started()

        mode.connect()
        mode.cancelSale()
        mode.printReceipt("receipt")

        assertEquals(1, online.connectCalls)
        assertEquals(1, online.cancelCalls)
        assertEquals(listOf("receipt"), online.printed)
        assertEquals(0, saf.cancelCalls)
    }

    // --- retries after a failed switch to online ------------------------------------------------

    /** Advances to just before and just after [atMs] (virtual time since the test started). */
    private fun TestScope.assertAttemptAt(atMs: Long, attemptsBefore: Int) {
        advanceTimeBy(atMs - testScheduler.currentTime - 1)
        runCurrent()
        assertEquals("attempts just before ${atMs}ms", attemptsBefore, online.activateCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("attempts at ${atMs}ms", attemptsBefore + 1, online.activateCalls)
    }

    @Test
    fun `failed switches to online back off 2, 5, 15, then every 30 minutes`() = runTest {
        online.connectsOnActivate = false
        network.status.value = NetworkStatus.ONLINE
        started() // attempt 1 at 0 s, fails at 30 s
        assertEquals(1, online.activateCalls)

        var failedAt = 30_000L
        var attempts = 1
        for (wait in listOf(2L, 5L, 15L, 30L, 30L)) {
            val next = failedAt + wait * 60_000
            assertAttemptAt(next, attempts)
            attempts++
            failedAt = next + 30_000
        }
        assertEquals(6, online.activateCalls)
    }

    @Test
    fun `the network coming back restarts the retries from the shortest wait`() = runTest {
        online.connectsOnActivate = false
        network.status.value = NetworkStatus.ONLINE
        started()
        assertAttemptAt(30_000 + 2 * 60_000, attemptsBefore = 1) // attempt 2, fails at 3 min
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(mode().state.value.onlineUnavailable) // now waiting 5 min

        network.status.value = NetworkStatus.OFFLINE
        runCurrent()
        network.status.value = NetworkStatus.ONLINE
        runCurrent()

        assertEquals(3, online.activateCalls) // tried again at once
        advanceTimeBy(30_000) // fails: the next wait is the shortest again
        runCurrent()
        assertAttemptAt(testScheduler.currentTime + 2 * 60_000, attemptsBefore = 3)
    }

    @Test
    fun `a new wait replaces the earlier one, so waits never overlap`() = runTest {
        online.connectsOnActivate = false
        network.status.value = NetworkStatus.ONLINE
        started() // fails at 30 s: the first wait would end at 2 min 30 s
        advanceTimeBy(30_000)
        runCurrent()

        advanceTimeBy(30_000) // at 1 min, the network drops and comes back: try again at once
        network.status.value = NetworkStatus.OFFLINE
        runCurrent()
        network.status.value = NetworkStatus.ONLINE
        runCurrent()
        assertEquals(2, online.activateCalls)
        advanceTimeBy(30_000) // fails at 1 min 30 s: the new wait ends at 3 min 30 s
        runCurrent()

        // The first wait, had it survived, would have unblocked online at 2 min 30 s.
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(2, online.activateCalls)
        assertAttemptAt(210_000, attemptsBefore = 2)
    }

    @Test
    fun `a retry waits while the merchant is on the charge screen`() = runTest {
        online.connectsOnActivate = false
        network.status.value = NetworkStatus.ONLINE
        val mode = started()
        advanceTimeBy(30_000)
        runCurrent()
        mode.enterChargeScreen()

        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(1, online.activateCalls)
        assertEquals(OnlineWait.WAITING_IDLE, mode.state.value.onlineWait)

        mode.leaveChargeScreen()
        runCurrent()
        assertEquals(2, online.activateCalls)
    }

    // --- a network that comes and goes ------------------------------------------------------------

    @Test
    fun `a network that drops every 30 seconds causes one switch, then online after 60 stable seconds`() = runTest {
        network.status.value = NetworkStatus.ONLINE
        val mode = started()
        assertEquals(ONLINE, mode.state.value.mode)

        repeat(10) { // 10 minutes: down 30 s, up 30 s
            network.status.value = NetworkStatus.OFFLINE
            advanceTimeBy(30_000)
            runCurrent()
            network.status.value = NetworkStatus.ONLINE
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(OFFLINE, mode.state.value.mode)
        }
        assertEquals(1, saf.activateCalls) // the one switch to offline
        assertEquals(1, online.activateCalls) // never back online while it flaps
        assertEquals(OnlineWait.NETWORK_SETTLING, mode.state.value.onlineWait)

        advanceTimeBy(30_001) // 60 s of stable network in all
        runCurrent()
        assertEquals(ONLINE, mode.state.value.mode)
        assertEquals(2, online.activateCalls)
    }

    @Test
    fun `a network that was down makes the next return a back online`() = runTest {
        network.status.value = NetworkStatus.ONLINE
        val mode = started()
        assertFalse(mode.state.value.wasOffline)

        network.status.value = NetworkStatus.OFFLINE
        runCurrent()

        assertTrue(mode.state.value.wasOffline)
    }

    @Test
    fun `a fallback that cannot connect store and forward is reported`() = runTest {
        online.connectsOnActivate = false
        saf.connectsOnActivate = false
        saf.connectionState.value = TerminalConnection.CONNECTED
        network.status.value = NetworkStatus.ONLINE
        val mode = started()

        advanceTimeBy(60_000) // 30 s for online, then 30 s waiting for Store and Forward
        runCurrent()

        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(listOf("switch to online", "switch to offline"), errors.summary().map { it.first })
    }

    private lateinit var current: PaymentModeCoordinator
    private fun mode() = current

    @Test
    fun `a short drop that ends during the switch to offline still waits 60 s before going online`() = runTest {
        network.status.value = NetworkStatus.ONLINE
        val mode = started()
        assertEquals(ONLINE, mode.state.value.mode)
        saf.connectsOnActivate = false // Store and Forward takes a while to connect

        network.status.value = NetworkStatus.OFFLINE
        runCurrent()
        assertEquals(OFFLINE, mode.state.value.switchingTo)
        advanceTimeBy(3_000)
        network.status.value = NetworkStatus.ONLINE // back while the switch still runs
        runCurrent()
        advanceTimeBy(2_000)
        saf.connectionState.value = TerminalConnection.CONNECTED // the switch ends, network up
        runCurrent()
        assertEquals(OFFLINE, mode.state.value.mode)
        assertEquals(OnlineWait.NETWORK_SETTLING, mode.state.value.onlineWait)

        advanceTimeBy(57_999) // 59.999 s since the network came back
        runCurrent()
        assertEquals(1, online.activateCalls) // no switch back yet

        advanceTimeBy(1)
        runCurrent()
        assertEquals(ONLINE, mode.state.value.mode)
        assertEquals(2, online.activateCalls)
    }
}
