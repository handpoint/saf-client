package com.handpoint.safclient.ui.sale

import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.domain.model.ProcessingStatus.SUCCEEDED
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.TakeSale
import com.handpoint.safclient.testutil.FakeNetworkMonitor
import com.handpoint.safclient.testutil.FakeOnlineTerminal
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.testutil.RecordingErrorReporter
import com.handpoint.safclient.testutil.paymentMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SaleViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val terminal = FakeTerminal()
    private val journal = InMemoryJournal()
    private val settings = FakePreferences()
    private val errors = RecordingErrorReporter()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val online = FakeOnlineTerminal()
    private val network = FakeNetworkMonitor()
    private lateinit var mode: PaymentModeCoordinator
    private lateinit var reconcile: ReconcileStoredSales

    /** The view model over [saf] and [journal], with a mode coordinator that stays offline unless started. */
    private fun TestScope.saleViewModel(
        saf: StoreAndForwardTerminal = terminal,
        journal: SalesJournal = this@SaleViewModelTest.journal,
    ): SaleViewModel {
        reconcile = ReconcileStoredSales(saf, journal, settings, clock)
        mode = paymentMode(saf, journal, clock, online = online, network = network, reconcile = reconcile, io = dispatcher)
        val takeSale = TakeSale(saf, online, journal, reconcile, mode, clock, errors)
        return SaleViewModel(takeSale, mode, settings, dispatcher, errors)
    }

    private fun SaleViewModel.enter(vararg digits: Int) = digits.forEach(::onDigit)

    private fun TestScope.messagesOf(viewModel: SaleViewModel): List<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch(dispatcher) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun `digits build the amount in minor units of the SAF currency`() = runTest(dispatcher) {
        val viewModel = saleViewModel()

        viewModel.enter(1, 2, 5, 0)

        assertEquals(Money(1250, USD), viewModel.amount.value)
    }

    @Test
    fun `backspace removes the last digit`() = runTest(dispatcher) {
        val viewModel = saleViewModel()
        viewModel.enter(1, 2, 5, 0)

        viewModel.onBackspace()

        assertEquals(Money(125, USD), viewModel.amount.value)
    }

    @Test
    fun `double zero appends two zeros`() = runTest(dispatcher) {
        val viewModel = saleViewModel()
        viewModel.enter(1, 2)

        viewModel.onDoubleZero()

        assertEquals(Money(1200, USD), viewModel.amount.value)
    }

    @Test
    fun `entry past the maximum is ignored and rejected`() = runTest(dispatcher) {
        val viewModel = saleViewModel()
        var rejections = 0
        backgroundScope.launch(dispatcher) { viewModel.entryRejected.collect { rejections++ } }
        viewModel.enter(9, 9, 9, 9, 9, 9, 9)

        viewModel.onDigit(1)
        viewModel.onDoubleZero()

        assertEquals(Money(SaleViewModel.MAX_MINOR_UNITS, USD), viewModel.amount.value)
        assertEquals(2, rejections)
    }

    @Test
    fun `charge at zero does not start a sale`() = runTest(dispatcher) {
        val viewModel = saleViewModel()

        viewModel.charge()

        assertEquals(0, terminal.saleCalls)
        assertEquals(ChargeUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `charge twice starts one sale`() = runTest(dispatcher) {
        terminal.saleGate = CompletableDeferred()
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)

        viewModel.charge()
        viewModel.charge()

        assertEquals(1, terminal.saleCalls)
        assertEquals(ChargeUiState.InProgress(null), viewModel.state.value)
        terminal.saleGate?.complete(Unit)
    }

    @Test
    fun `keypad is ignored while a sale is in progress`() = runTest(dispatcher) {
        terminal.saleGate = CompletableDeferred()
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        viewModel.onDigit(1)
        viewModel.onBackspace()

        assertEquals(Money(500, USD), viewModel.amount.value)
        terminal.saleGate?.complete(Unit)
    }

    @Test
    fun `card steps show while the sale runs`() = runTest(dispatcher) {
        terminal.saleGate = CompletableDeferred()
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        val step = CardStep(CardStep.Kind.ENTER_PIN, cancelAllowed = false)
        terminal.emitCardStep(step)

        assertEquals(ChargeUiState.InProgress(step), viewModel.state.value)
        terminal.saleGate?.complete(Unit)
    }

    @Test
    fun `outcome is surfaced in Result`() = runTest(dispatcher) {
        val approved = sale("A", minorUnits = 500)
        terminal.saleOutcome = SaleOutcome.ApprovedOffline(approved)
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)

        viewModel.charge()

        assertEquals(ChargeUiState.Result(SaleOutcome.ApprovedOffline(approved)), viewModel.state.value)
        assertEquals(approved, journal.find(approved.reference))
    }

    @Test
    fun `decline is surfaced in Result`() = runTest(dispatcher) {
        terminal.saleOutcome = SaleOutcome.Declined(DeclineReason.PREPAID_CARD)
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)

        viewModel.charge()

        assertEquals(ChargeUiState.Result(SaleOutcome.Declined(DeclineReason.PREPAID_CARD)), viewModel.state.value)
    }

    @Test
    fun `unknown outcome has its own result without try again`() = runTest(dispatcher) {
        val unknown = SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN)
        terminal.saleOutcome = unknown
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)

        viewModel.charge()

        assertEquals(ChargeUiState.Result(unknown), viewModel.state.value)
        val copy = resultCopy(unknown, USD)
        assertEquals("Couldn't confirm the sale", copy.title)
        assertEquals(
            "Check Home before charging again — the sale may already be stored on this terminal.",
            copy.body,
        )
        assertFalse(copy.canTryAgain)
        assertFalse(copy.canPrint)
    }

    @Test
    fun `other failures can be tried again`() {
        val copy = resultCopy(SaleOutcome.Failed("Terminal busy"), USD)

        assertEquals("Sale not completed", copy.title)
        assertEquals("Terminal busy", copy.body) // the SDK's message, when it is readable
        assertTrue(copy.canTryAgain)
    }

    @Test
    fun `currency decline names the currency`() {
        val copy = resultCopy(SaleOutcome.Declined(DeclineReason.CURRENCY_NOT_ACCEPTED), USD)

        assertEquals("This terminal doesn't take offline sales in USD. Ask Handpoint to check the terminal's currency.", copy.body)
        // Another card would be refused too.
        assertFalse(copy.canTryAgain)
    }

    @Test
    fun `cancelled sale returns to the amount with a message`() = runTest(dispatcher) {
        terminal.saleOutcome = SaleOutcome.Cancelled
        val viewModel = saleViewModel()
        val messages = messagesOf(viewModel)
        viewModel.enter(5, 0, 0)

        viewModel.charge()

        assertEquals(ChargeUiState.Idle, viewModel.state.value)
        assertEquals(Money(500, USD), viewModel.amount.value)
        assertEquals(listOf("Sale cancelled."), messages)
    }

    @Test
    fun `try again returns to the amount with the same amount`() = runTest(dispatcher) {
        terminal.saleOutcome = SaleOutcome.Declined(DeclineReason.CARD_EXPIRED)
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        viewModel.tryAgain()

        assertEquals(ChargeUiState.Idle, viewModel.state.value)
        assertEquals(Money(500, USD), viewModel.amount.value)
    }

    @Test
    fun `cancel asks the terminal only while the step allows it`() = runTest(dispatcher) {
        terminal.saleGate = CompletableDeferred()
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        terminal.emitCardStep(CardStep(CardStep.Kind.PROCESSING, cancelAllowed = false))
        viewModel.cancel()
        terminal.emitCardStep(CardStep(CardStep.Kind.PRESENT_CARD, cancelAllowed = true))
        viewModel.cancel()

        assertEquals(1, terminal.cancelCalls)
        terminal.saleGate?.complete(Unit)
    }

    @Test
    fun `print sends the customer receipt`() = runTest(dispatcher) {
        val approved = sale("A").copy(merchantReceipt = "merchant copy", customerReceipt = "customer copy")
        terminal.saleOutcome = SaleOutcome.ApprovedOffline(approved)
        val viewModel = saleViewModel()
        val messages = messagesOf(viewModel)
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        viewModel.printReceipt()

        assertEquals(listOf("customer copy"), terminal.printed)
        assertTrue(messages.isEmpty())
        assertFalse(viewModel.printing.value)
    }

    @Test
    fun `print failure shows a message`() = runTest(dispatcher) {
        terminal.printAccepted = false
        val approved = sale("A").copy(customerReceipt = "customer copy")
        terminal.saleOutcome = SaleOutcome.ApprovedOffline(approved)
        val viewModel = saleViewModel()
        val messages = messagesOf(viewModel)
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        viewModel.printReceipt()

        assertEquals(listOf("Couldn't print the receipt."), messages)
    }

    @Test
    fun `print without any receipt shows a message`() = runTest(dispatcher) {
        terminal.saleOutcome = SaleOutcome.ApprovedOffline(sale("A").copy(customerReceipt = " "))
        val viewModel = saleViewModel()
        val messages = messagesOf(viewModel)
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        viewModel.printReceipt()

        assertTrue(terminal.printed.isEmpty())
        assertEquals(listOf("Couldn't print the receipt."), messages)
    }

    @Test
    fun `a sale that throws ends as an unknown outcome and is reported`() = runTest(dispatcher) {
        val viewModel = saleViewModel(saf = ThrowingSaleTerminal(terminal))
        viewModel.enter(5, 0, 0)

        viewModel.charge()

        assertEquals(ChargeUiState.Result(UNKNOWN), viewModel.state.value)
        assertEquals(listOf("sale" to "SDK gone"), errors.summary())
    }

    @Test
    fun `journal failure after approval ends as an unknown outcome without try again`() = runTest(dispatcher) {
        terminal.saleOutcome = SaleOutcome.ApprovedOffline(sale("A"))
        val viewModel = saleViewModel(journal = RecordFailingJournal(journal))
        viewModel.enter(5, 0, 0)

        viewModel.charge()
        viewModel.tryAgain()

        assertEquals(ChargeUiState.Result(UNKNOWN), viewModel.state.value)
        assertFalse(resultCopy(UNKNOWN, USD).canTryAgain)
        assertEquals(listOf("sale" to "disk full"), errors.summary())
    }

    @Test
    fun `reconcile failure after an unknown outcome ends as an unknown outcome`() = runTest(dispatcher) {
        terminal.saleOutcome = UNKNOWN
        terminal.storedError = IllegalStateException("no answer")
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)

        viewModel.charge()

        assertEquals(ChargeUiState.Result(UNKNOWN), viewModel.state.value)
        assertEquals(listOf("sale" to "no answer"), errors.summary())
    }

    @Test
    fun `try again is ignored after an unknown outcome`() = runTest(dispatcher) {
        terminal.saleOutcome = UNKNOWN
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        viewModel.tryAgain()

        assertEquals(ChargeUiState.Result(UNKNOWN), viewModel.state.value)
    }

    @Test
    fun `try again is ignored after an approval`() = runTest(dispatcher) {
        val approved = SaleOutcome.ApprovedOffline(sale("A"))
        terminal.saleOutcome = approved
        val viewModel = saleViewModel()
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        viewModel.tryAgain()

        assertEquals(ChargeUiState.Result(approved), viewModel.state.value)
    }

    @Test
    fun `refused cancel shows a message`() = runTest(dispatcher) {
        terminal.saleGate = CompletableDeferred()
        terminal.cancelAccepted = false
        val viewModel = saleViewModel()
        val messages = messagesOf(viewModel)
        viewModel.enter(5, 0, 0)
        viewModel.charge()
        terminal.emitCardStep(CardStep(CardStep.Kind.PRESENT_CARD, cancelAllowed = true))

        viewModel.cancel()

        assertEquals(listOf("Can't cancel now — the card has been read."), messages)
        terminal.saleGate?.complete(Unit)
    }

    // --- online mode ---------------------------------------------------------------------------

    /** Network up and nothing stored on the terminal: the coordinator switches to online sales. */
    private suspend fun goOnline(viewModel: SaleViewModel) {
        network.status.value = NetworkStatus.ONLINE
        reconcile()
        mode.start()
        assertEquals(PaymentMode.ONLINE, viewModel.mode.value.mode)
    }

    @Test
    fun `online, the sale is taken online and its receipt printed through the online API`() = runTest(dispatcher) {
        val viewModel = saleViewModel()
        goOnline(viewModel)
        val approved = SaleOutcome.ApprovedOnline(sale("ON", SUCCEEDED).copy(customerReceipt = "online receipt"))
        online.saleOutcome = approved

        viewModel.enter(5, 0, 0)
        viewModel.charge()
        viewModel.printReceipt()

        assertEquals(ChargeUiState.Result(approved), viewModel.state.value)
        assertEquals(1, online.saleCalls)
        assertEquals(0, terminal.saleCalls)
        assertEquals(listOf("online receipt"), online.printed)
        assertTrue(terminal.printed.isEmpty())
    }

    @Test
    fun `online, card steps and cancel use the online API`() = runTest(dispatcher) {
        val viewModel = saleViewModel()
        goOnline(viewModel)
        online.saleGate = CompletableDeferred()
        viewModel.enter(5, 0, 0)
        viewModel.charge()

        val step = CardStep(CardStep.Kind.PRESENT_CARD, cancelAllowed = true)
        online.emitCardStep(step)
        assertEquals(ChargeUiState.InProgress(step), viewModel.state.value)

        viewModel.cancel()
        assertEquals(1, online.cancelCalls)
        assertEquals(0, terminal.cancelCalls)
        online.saleGate?.complete(Unit)
    }

    @Test
    fun `online, a sale that throws ends as an online unknown outcome`() = runTest(dispatcher) {
        val viewModel = saleViewModel(journal = RecordFailingJournal(journal))
        goOnline(viewModel)
        online.saleOutcome = SaleOutcome.ApprovedOnline(sale("ON", SUCCEEDED))

        viewModel.enter(5, 0, 0)
        viewModel.charge()

        assertEquals(ChargeUiState.Result(SaleOutcome.Failed(OnlineTerminal.OUTCOME_UNKNOWN)), viewModel.state.value)
    }

    @Test
    fun `charge while the mode switches stays on the amount with a message`() = runTest(dispatcher) {
        val viewModel = saleViewModel()
        val messages = messagesOf(viewModel)
        online.connectsOnActivate = false
        network.status.value = NetworkStatus.ONLINE
        reconcile()
        mode.start()
        assertTrue(viewModel.mode.value.isSwitching)

        viewModel.enter(5, 0, 0)
        viewModel.charge()

        assertEquals(ChargeUiState.Idle, viewModel.state.value)
        assertEquals(0, online.saleCalls + terminal.saleCalls)
        assertEquals(listOf("Switching between online and offline sales. Try again in a moment."), messages)
    }

    // --- declines of the amount ------------------------------------------------------------------

    @Test
    fun `the result copy uses the SDK's message and our own when there is none`() {
        val message = "This sale is over the offline limit of $1.00 per sale."
        assertEquals(message, resultCopy(SaleOutcome.Declined(DeclineReason.OVER_SALE_LIMIT, message), USD).body)
        assertEquals(
            "This sale is over the terminal's offline limit per sale. Change the amount.",
            resultCopy(SaleOutcome.Declined(DeclineReason.OVER_SALE_LIMIT), USD).body,
        )
        assertEquals("Device busy", resultCopy(SaleOutcome.Failed("Device busy"), USD).body)
        assertEquals("The sale couldn't be completed.", resultCopy(SaleOutcome.Failed("TEXT_DEFAULT_ERROR_MSG"), USD).body)
    }

    @Test
    fun `each decline reason offers its actions`() {
        val changeAmount = setOf(DeclineReason.OVER_SALE_LIMIT, DeclineReason.INVALID_AMOUNT, DeclineReason.TERMINAL_LIMIT_REACHED)
        for (reason in DeclineReason.entries) {
            val copy = resultCopy(SaleOutcome.Declined(reason), USD)
            assertEquals("change amount for $reason", reason in changeAmount, copy.canChangeAmount)
            val tryAgain = reason !in changeAmount && reason != DeclineReason.CURRENCY_NOT_ACCEPTED
            assertEquals("try again for $reason", tryAgain, copy.canTryAgain)
        }
    }

    @Test
    fun `change amount returns to the amount with the amount kept, and try again is ignored`() = runTest(dispatcher) {
        terminal.saleOutcome = SaleOutcome.Declined(DeclineReason.OVER_SALE_LIMIT, "Over the limit")
        val viewModel = saleViewModel()
        viewModel.enter(3, 0, 1, 0, 0)
        viewModel.charge()

        viewModel.tryAgain()
        assertTrue(viewModel.state.value is ChargeUiState.Result)

        viewModel.changeAmount()
        assertEquals(ChargeUiState.Idle, viewModel.state.value)
        assertEquals(30100L, viewModel.amount.value.minorUnits)
    }

    private companion object {
        val UNKNOWN = SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN)
    }
}

/** Journal whose [record] fails, as when the disk is full. */
private class RecordFailingJournal(
    private val delegate: SalesJournal,
) : SalesJournal by delegate {
    override suspend fun record(sale: Sale) = throw IllegalStateException("disk full")
}

/** Terminal whose sale throws, as when the SDK cannot be created. */
private class ThrowingSaleTerminal(
    private val delegate: StoreAndForwardTerminal,
) : StoreAndForwardTerminal by delegate {
    override suspend fun sale(amount: Money): SaleOutcome = throw IllegalStateException("SDK gone")
}
