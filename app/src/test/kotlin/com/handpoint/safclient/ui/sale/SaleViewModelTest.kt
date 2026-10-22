package com.handpoint.safclient.ui.sale

import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.TakeSale
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.testutil.RecordingErrorReporter
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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

    private fun saleViewModel(): SaleViewModel {
        val takeSale = TakeSale(terminal, journal, ReconcileStoredSales(terminal, journal, settings, clock))
        return SaleViewModel(takeSale, terminal, settings, dispatcher, errors)
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
        assertEquals("The sale couldn't be completed.", copy.body)
        assertTrue(copy.canTryAgain)
    }

    @Test
    fun `currency decline names the currency`() {
        val copy = resultCopy(SaleOutcome.Declined(DeclineReason.CURRENCY_NOT_ACCEPTED), USD)

        assertEquals("This terminal takes offline sales in USD only.", copy.body)
        assertTrue(copy.canTryAgain)
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
    fun `a sale that throws ends as not completed and is reported`() = runTest(dispatcher) {
        val failing = ThrowingSaleTerminal(terminal)
        val takeSale = TakeSale(failing, journal, ReconcileStoredSales(failing, journal, settings, clock))
        val viewModel = SaleViewModel(takeSale, failing, settings, dispatcher, errors)
        viewModel.enter(5, 0, 0)

        viewModel.charge()

        val state = viewModel.state.value as ChargeUiState.Result
        assertTrue(state.outcome is SaleOutcome.Failed)
        assertEquals(listOf("sale" to "SDK gone"), errors.summary())
    }
}

/** Terminal whose sale throws, as when the SDK cannot be created. */
private class ThrowingSaleTerminal(
    private val delegate: StoreAndForwardTerminal,
) : StoreAndForwardTerminal by delegate {
    override suspend fun sale(amount: Money): SaleOutcome = throw IllegalStateException("SDK gone")
}
