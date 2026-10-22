package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.ForwardingEvent.Finished
import com.handpoint.safclient.domain.model.ForwardingEvent.Sent
import com.handpoint.safclient.domain.model.ForwardingEvent.Started
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.NetworkStatus
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
import java.time.Instant

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
        network.status.value = NetworkStatus.ONLINE
        val mode = coordinator()

        mode.start()
        runCurrent()
        assertEquals(OFFLINE, mode.state.value.mode)

        reconcile()
        runCurrent()
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
        advanceTimeBy(PaymentModeCoordinator.RETRY_ONLINE_AFTER.inWholeMilliseconds)
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
}
