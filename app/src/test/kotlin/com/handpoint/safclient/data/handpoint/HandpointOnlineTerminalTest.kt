package com.handpoint.safclient.data.handpoint

import com.handpoint.api.shared.ConnectionMethod
import com.handpoint.api.shared.ConnectionStatus
import com.handpoint.api.shared.Events
import com.handpoint.api.shared.FinancialStatus
import com.handpoint.api.shared.OperationStartResult
import com.handpoint.api.shared.TransactionResult
import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.testutil.FakeHapi
import com.handpoint.safclient.testutil.FakeSafApi.Companion.DEVICE
import com.handpoint.safclient.testutil.FakeSafApi.Companion.SALE_REFERENCE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, currentTime
class HandpointOnlineTerminalTest {

    private val amount = Money(1250, Currency.getInstance("USD"))
    private val now = Instant.parse("2026-10-22T10:15:30Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val created = mutableListOf<FakeHapi>()
    private val fake get() = created.last()

    private fun newTerminal() = HandpointOnlineTerminal(
        apiFactory = { _ -> FakeHapi().also { created += it } },
        clock = clock,
    )

    private fun activeTerminal() = newTerminal().also { it.activate() }

    // --- lifecycle ---------------------------------------------------------------------------

    @Test
    fun `nothing is created until it is activated`() {
        val terminal = newTerminal()

        assertFalse(terminal.connect())
        assertFalse(terminal.cancelSale())
        assertTrue(created.isEmpty())
    }

    @Test
    fun `activate creates the API, registers for events and connects to the terminal itself`() {
        val terminal = newTerminal()

        assertTrue(terminal.activate())

        assertEquals(
            listOf("registerEventsDelegate", "getPairedDevices(${ConnectionMethod.ANDROID_PAYMENT})", "connect"),
            fake.calls,
        )
    }

    @Test
    fun `each activation creates the API again`() {
        val terminal = newTerminal()
        terminal.activate()
        terminal.deactivate()
        terminal.activate()

        assertEquals(2, created.size)
        assertEquals(listOf("registerEventsDelegate", "getPairedDevices(${ConnectionMethod.ANDROID_PAYMENT})", "connect", "disconnect"), created[0].calls)
    }

    @Test
    fun `activate fails when the terminal lists no payment device`() {
        val terminal = HandpointOnlineTerminal(
            apiFactory = { FakeHapi().apply { pairedDevices = emptyList() }.also { created += it } },
            clock = clock,
        )

        assertFalse(terminal.activate())
    }

    @Test
    fun `connection follows the SDK and is reset when deactivated`() = runTest {
        val terminal = activeTerminal()
        fake.fire<Events.ConnectionStatusChanged> { it.connectionStatusChanged(ConnectionStatus.Connected, DEVICE) }
        assertEquals(TerminalConnection.CONNECTED, terminal.connection.value)

        terminal.deactivate()

        assertEquals(TerminalConnection.DISCONNECTED, terminal.connection.value)
    }

    @Test
    fun `a deactivated terminal does not take sales`() = runTest {
        val terminal = activeTerminal()
        terminal.deactivate()
        val callsBefore = fake.calls.size

        assertTrue(terminal.sale(amount) is SaleOutcome.Failed)
        assertEquals(callsBefore, fake.calls.size)
    }

    // --- sale outcomes -----------------------------------------------------------------------

    @Test
    fun `an authorised sale is approved online, paid, and keeps its receipts`() = runTest {
        val terminal = activeTerminal()
        fake.duringSale = {
            fire<Events.EndOfTransaction> { it.endOfTransaction(result(FinancialStatus.AUTHORISED), DEVICE) }
        }

        val outcome = terminal.sale(amount) as SaleOutcome.ApprovedOnline

        val sale = outcome.sale
        assertEquals(SaleReference(SALE_REFERENCE), sale.reference)
        assertEquals(amount, sale.amount)
        assertEquals(SaleChannel.ONLINE, sale.channel)
        assertEquals(ProcessingStatus.SUCCEEDED, sale.status)
        assertEquals(now, sale.approvedAt)
        assertEquals("•••• 4242", sale.maskedCard)
        assertEquals("customer copy", sale.customerReceipt)
        assertTrue(fake.calls.contains("sale(1250, USD)"))
    }

    @Test
    fun `a declined sale shows the SDK message`() = runTest {
        val terminal = activeTerminal()
        fake.duringSale = {
            fire<Events.EndOfTransaction> {
                it.endOfTransaction(result(FinancialStatus.DECLINED, error = "Insufficient funds"), DEVICE)
            }
        }

        assertEquals(SaleOutcome.DeclinedOnline("Insufficient funds"), terminal.sale(amount))
    }

    @Test
    fun `a declined sale without a message still says declined`() = runTest {
        val terminal = activeTerminal()
        fake.duringSale = {
            fire<Events.EndOfTransaction> { it.endOfTransaction(result(FinancialStatus.DECLINED), DEVICE) }
        }

        assertEquals(SaleOutcome.DeclinedOnline("Declined"), terminal.sale(amount))
    }

    @Test
    fun `a cancelled sale is cancelled`() = runTest {
        val terminal = activeTerminal()
        fake.duringSale = {
            fire<Events.EndOfTransaction> { it.endOfTransaction(result(FinancialStatus.CANCELLED), DEVICE) }
        }

        assertEquals(SaleOutcome.Cancelled, terminal.sale(amount))
    }

    @Test
    fun `a failed sale fails with the SDK message`() = runTest {
        val terminal = activeTerminal()
        fake.duringSale = {
            fire<Events.EndOfTransaction> { it.endOfTransaction(result(FinancialStatus.FAILED, error = "Card removed"), DEVICE) }
        }

        assertEquals(SaleOutcome.Failed("Card removed"), terminal.sale(amount))
    }

    @Test
    fun `an unexpected status is an unknown outcome`() = runTest {
        val terminal = activeTerminal()
        fake.duringSale = {
            fire<Events.EndOfTransaction> { it.endOfTransaction(result(FinancialStatus.PARTIAL_APPROVAL), DEVICE) }
        }

        assertEquals(SaleOutcome.Failed(OnlineTerminal.OUTCOME_UNKNOWN), terminal.sale(amount))
    }

    @Test
    fun `no result within 180 seconds is an unknown outcome and the sale is stopped`() = runTest {
        val terminal = activeTerminal()

        val outcome = terminal.sale(amount)

        assertEquals(SaleOutcome.Failed(OnlineTerminal.OUTCOME_UNKNOWN), outcome)
        assertEquals(180_000L, testScheduler.currentTime)
        assertEquals(1, fake.calls.count { it == "stopCurrentTransaction" })
    }

    @Test
    fun `cancelling the caller stops the sale on the terminal`() = runTest {
        val terminal = activeTerminal()

        val sale = launch { terminal.sale(amount) }
        runCurrent()
        sale.cancel()
        sale.join()

        assertEquals(1, fake.calls.count { it == "stopCurrentTransaction" })
    }

    @Test
    fun `a sale the SDK does not start fails with its message`() = runTest {
        val terminal = activeTerminal()
        fake.saleStart = OperationStartResult(false, errorMessage = "Device busy")

        assertEquals(SaleOutcome.Failed("Device busy"), terminal.sale(amount))
    }

    @Test
    fun `a sale the SDK rejects without a message ends with the reason it sends`() = runTest {
        val terminal = activeTerminal()
        fake.saleStart = OperationStartResult(false)
        fake.duringSale = {
            fire<Events.EndOfTransaction> {
                it.endOfTransaction(result(FinancialStatus.DECLINED, reference = "", error = "Invalid amount"), DEVICE)
            }
        }

        assertEquals(SaleOutcome.DeclinedOnline("Invalid amount"), terminal.sale(amount))
    }

    // --- cancel and print --------------------------------------------------------------------

    @Test
    fun `cancel and print go to the active API`() {
        val terminal = activeTerminal()

        assertTrue(terminal.cancelSale())
        assertTrue(terminal.printReceipt("receipt"))

        assertTrue(fake.calls.containsAll(listOf("stopCurrentTransaction", "printReceipt(receipt)")))
    }

    private fun result(status: FinancialStatus, reference: String = SALE_REFERENCE, error: String = "") =
        TransactionResult().apply {
            finStatus = status
            transactionReference = reference
            maskedCardNumber = "************4242"
            errorMessage = error
            customerReceipt = "customer copy"
        }
}
