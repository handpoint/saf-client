package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.SUCCEEDED
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.testutil.FakeNetworkMonitor
import com.handpoint.safclient.testutil.FakeOnlineTerminal
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.testutil.RecordingErrorReporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TakeSaleTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val terminal = FakeTerminal()
    private val online = FakeOnlineTerminal()
    private val network = FakeNetworkMonitor(NetworkStatus.OFFLINE)
    private val journal = InMemoryJournal()
    private val reconcile = ReconcileStoredSales(terminal, journal, FakePreferences(), clock)

    private lateinit var mode: PaymentModeCoordinator

    private fun TestScope.takeSale(): TakeSale {
        val statuses = RefreshOpenStatuses(journal, RefreshSaleStatus(terminal, journal, clock), clock, backgroundScope)
        mode = PaymentModeCoordinator(
            saf = terminal,
            online = online,
            network = network,
            journal = journal,
            reconcile = reconcile,
            send = SendStoredSales(terminal, journal, reconcile, statuses, clock, backgroundScope),
            statuses = statuses,
            errors = RecordingErrorReporter(),
            appScope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
        )
        return TakeSale(terminal, online, journal, reconcile, mode)
    }

    /** Network up and nothing stored: the coordinator switches to the online API. */
    private suspend fun TestScope.goOnline() {
        reconcile()
        network.status.value = NetworkStatus.ONLINE
        mode.start()
        runCurrent()
        assertEquals(PaymentMode.ONLINE, mode.state.value.mode)
    }

    // --- offline (Store and Forward) -----------------------------------------------------------

    @Test
    fun `approved sale is recorded in the journal`() = runTest {
        val takeSale = takeSale()
        val approved = sale("A")
        terminal.saleOutcome = SaleOutcome.ApprovedOffline(approved)

        val outcome = takeSale(Money(1000, USD))

        assertEquals(SaleOutcome.ApprovedOffline(approved), outcome)
        assertEquals(approved, journal.find(SaleReference("A")))
        assertEquals(1, terminal.saleCalls)
        assertEquals(0, online.saleCalls)
    }

    @Test
    fun `declined sale is not recorded`() = runTest {
        val takeSale = takeSale()
        terminal.saleOutcome = SaleOutcome.Declined(DeclineReason.CARD_EXPIRED)

        val outcome = takeSale(Money(1000, USD))

        assertEquals(SaleOutcome.Declined(DeclineReason.CARD_EXPIRED), outcome)
        assertTrue(journal.observeAll().first().isEmpty())
        assertEquals(0, terminal.storedCalls)
    }

    @Test
    fun `unknown outcome reconciles the journal with the terminal`() = runTest {
        val takeSale = takeSale()
        journal.record(sale("OLD"))
        terminal.saleOutcome = SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN)
        terminal.storedSummary = StoredSummary(emptyList(), null)

        val outcome = takeSale(Money(1000, USD))

        assertEquals(SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN), outcome)
        assertEquals(1, terminal.storedCalls)
        assertEquals(IN_PROGRESS, journal.status("OLD"))
    }

    @Test
    fun `other failures do not reconcile`() = runTest {
        val takeSale = takeSale()
        terminal.saleOutcome = SaleOutcome.Failed("Terminal busy")

        takeSale(Money(1000, USD))

        assertEquals(0, terminal.storedCalls)
        assertTrue(journal.observeAll().first().isEmpty())
    }

    // --- online --------------------------------------------------------------------------------

    @Test
    fun `online mode takes the sale online and records the approval as an online, paid sale`() = runTest {
        val takeSale = takeSale()
        goOnline()
        val approved = sale("ON", SUCCEEDED).copy(channel = SaleChannel.ONLINE)
        online.saleOutcome = SaleOutcome.ApprovedOnline(approved)

        val outcome = takeSale(Money(1000, USD))

        assertEquals(SaleOutcome.ApprovedOnline(approved), outcome)
        val recorded = journal.find(SaleReference("ON"))!!
        assertEquals(SaleChannel.ONLINE, recorded.channel)
        assertEquals(SUCCEEDED, recorded.status)
        assertEquals(1, online.saleCalls)
        assertEquals(0, terminal.saleCalls)
    }

    @Test
    fun `an online decline is not recorded`() = runTest {
        val takeSale = takeSale()
        goOnline()
        online.saleOutcome = SaleOutcome.DeclinedOnline("Insufficient funds")

        assertEquals(SaleOutcome.DeclinedOnline("Insufficient funds"), takeSale(Money(1000, USD)))
        assertTrue(journal.observeAll().first().isEmpty())
    }

    @Test
    fun `an unknown online outcome does not reconcile store and forward`() = runTest {
        val takeSale = takeSale()
        goOnline()
        val storedCallsBefore = terminal.storedCalls
        online.saleOutcome = SaleOutcome.Failed(OnlineTerminal.OUTCOME_UNKNOWN)

        takeSale(Money(1000, USD))

        assertEquals(storedCallsBefore, terminal.storedCalls)
    }

    // --- switching -----------------------------------------------------------------------------

    @Test
    fun `no sale starts while the mode switches`() = runTest {
        val takeSale = takeSale()
        online.connectsOnActivate = false
        reconcile()
        network.status.value = NetworkStatus.ONLINE
        mode.start()
        runCurrent()

        val outcome = takeSale(Money(1000, USD))

        assertEquals(SaleOutcome.Failed(PaymentModeCoordinator.SWITCHING), outcome)
        assertEquals(0, terminal.saleCalls)
        assertEquals(0, online.saleCalls)
    }

    @Test
    fun `a sale in progress holds the mode until it ends`() = runTest {
        val takeSale = takeSale()
        reconcile()
        mode.start()
        terminal.saleGate = CompletableDeferred()
        terminal.saleOutcome = SaleOutcome.ApprovedOffline(sale("A"))
        val outcome = async { takeSale(Money(1000, USD)) }
        runCurrent()

        network.status.value = NetworkStatus.ONLINE
        runCurrent()
        assertEquals(PaymentMode.OFFLINE, mode.state.value.mode)

        terminal.saleGate!!.complete(Unit)
        outcome.await()
        runCurrent()
        // The sale was stored offline, so the app stays on Store and Forward to send it.
        assertEquals(PaymentMode.OFFLINE, mode.state.value.mode)
        assertEquals(1, mode.state.value.waitingToSend)
    }
}
