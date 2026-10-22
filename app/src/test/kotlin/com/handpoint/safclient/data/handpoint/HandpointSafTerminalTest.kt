package com.handpoint.safclient.data.handpoint

import app.cash.turbine.test
import com.handpoint.api.shared.Events
import com.handpoint.api.shared.ConnectionStatus
import com.handpoint.api.shared.FinancialStatus
import com.handpoint.api.shared.OperationStartResult
import com.handpoint.api.shared.SafTransactionStatus
import com.handpoint.api.shared.StatusInfo
import com.handpoint.api.shared.TransactionResult
import com.handpoint.api.shared.storeandforward.ForwardingTransactionStatus
import com.handpoint.api.shared.storeandforward.TransactionDeletionFailed
import com.handpoint.api.shared.storeandforward.TransactionReference
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.testutil.FakeSafApi
import com.handpoint.safclient.testutil.FakeSafApi.Companion.DEVICE
import com.handpoint.safclient.testutil.FakeSafApi.Companion.OTHER_REFERENCE
import com.handpoint.safclient.testutil.FakeSafApi.Companion.SALE_REFERENCE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import com.handpoint.api.shared.Currency as SdkCurrency

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, currentTime
class HandpointSafTerminalTest {

    private val usd = Currency.getInstance("USD")
    private val amount = Money(8450, usd)
    private val now = Instant.parse("2026-10-22T10:15:30Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val fake = FakeSafApi()
    private var factoryCalls = 0

    private fun newTerminal() = HandpointSafTerminal(
        apiFactory = { _ -> factoryCalls++; fake },
        clock = clock,
    )

    // --- lifecycle ---------------------------------------------------------------------------

    @Test
    fun `a connect while a start is in flight does not start the SDK again`() {
        val terminal = newTerminal()
        val inStart = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.duringStart = {
            inStart.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val first = thread { terminal.connect() }
        assertTrue(inStart.await(5, TimeUnit.SECONDS))

        assertTrue(terminal.connect()) // joins the start in flight

        release.countDown()
        first.join(5_000)
        assertEquals(listOf("start"), fake.calls.filter { it == "start" })

        fake.duringStart = {}
        terminal.connect() // the start has ended: a new connect starts again
        assertEquals(2, fake.calls.count { it == "start" })
    }

    @Test
    fun `creates the SDK lazily, once, and registers the bridge for SAF events`() = runTest {
        val terminal = newTerminal()
        assertEquals(0, factoryCalls)

        assertTrue(terminal.connect())
        assertTrue(terminal.disconnect())
        assertTrue(terminal.connect())

        assertEquals(1, factoryCalls)
        assertEquals(listOf("registerEventsDelegate", "start", "stop", "start"), fake.calls)
    }

    @Test
    fun `connection follows the SDK connection status`() = runTest {
        val terminal = newTerminal()
        terminal.connect()
        assertEquals(TerminalConnection.DISCONNECTED, terminal.connection.value)

        fake.fire<Events.ConnectionStatusChanged> { it.connectionStatusChanged(ConnectionStatus.Connected, DEVICE) }
        runCurrent()

        assertEquals(TerminalConnection.CONNECTED, terminal.connection.value)
    }

    @Test
    fun `card steps are emitted and statuses that are not card prompts are dropped`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        terminal.cardSteps.test {
            fake.fire<Events.CurrentTransactionStatus> {
                it.currentTransactionStatus(StatusInfo(status = StatusInfo.Status.PrintingMerchantReceipt), DEVICE)
                it.currentTransactionStatus(
                    StatusInfo(isCancelAllowed = true, status = StatusInfo.Status.WaitingForCard),
                    DEVICE,
                )
            }
            assertEquals(CardStep(CardStep.Kind.PRESENT_CARD, cancelAllowed = true), awaitItem())
        }
    }

    // --- sale --------------------------------------------------------------------------------

    @Test
    fun `sale is approved offline when the end of transaction for its reference arrives`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        val outcome = async { terminal.sale(amount) }
        runCurrent()
        fake.fire<Events.EndOfTransaction> {
            it.endOfTransaction(approvedResult(OTHER_REFERENCE), DEVICE) // not ours: ignored
            it.endOfTransaction(approvedResult(SALE_REFERENCE), DEVICE)
        }

        val approved = outcome.await() as SaleOutcome.ApprovedOffline
        assertEquals(SaleReference(SALE_REFERENCE), approved.sale.reference)
        assertEquals(amount, approved.sale.amount)
        assertEquals(now, approved.sale.approvedAt)
        assertEquals(ProcessingStatus.STORED, approved.sale.status)
        assertTrue(fake.calls.contains("sale(8450, USD)"))
    }

    @Test
    fun `sale result delivered before the SDK call returns is not missed`() = runTest {
        val terminal = newTerminal()
        terminal.connect()
        fake.duringSale = {
            fire<Events.EndOfTransaction> { it.endOfTransaction(approvedResult(SALE_REFERENCE), DEVICE) }
        }

        val outcome = terminal.sale(amount)

        assertTrue(outcome is SaleOutcome.ApprovedOffline)
    }

    @Test
    fun `sale fails with the SDK message when the SDK does not start it`() = runTest {
        val terminal = newTerminal()
        terminal.connect()
        fake.saleStart = OperationStartResult(false, errorMessage = "x")

        assertEquals(SaleOutcome.Failed("x"), terminal.sale(amount))
        assertEquals(0L, testScheduler.currentTime) // a refusal with a message does not wait
    }

    @Test
    fun `sale refused without a message or reason fails after the short rejection wait`() = runTest {
        val terminal = newTerminal()
        terminal.connect()
        fake.saleStart = OperationStartResult(false)

        assertEquals(SaleOutcome.Failed("The terminal did not start the sale"), terminal.sale(amount))
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `sale refused by an SDK check is declined with the reason the SDK sends`() = runTest {
        val terminal = newTerminal()
        terminal.connect()
        // A failed SDK check returns OperationStartResult(false) with no message and sends
        // the reason as an end of transaction without a reference.
        fake.saleStart = OperationStartResult(false)
        fake.duringSale = {
            fire<Events.EndOfTransaction> {
                it.endOfTransaction(failedResult("Invalid currency: USD. Expected: GBP", reference = ""), DEVICE)
            }
        }

        assertEquals(SaleOutcome.Declined(DeclineReason.CURRENCY_NOT_ACCEPTED), terminal.sale(amount))
    }

    @Test
    fun `sale outcome is unknown and the sale is stopped when no result arrives within 180 seconds`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        val outcome = terminal.sale(amount)

        assertEquals(SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN), outcome)
        assertEquals(180_000L, testScheduler.currentTime)
        assertEquals(1, fake.calls.count { it == "stopCurrentTransaction" })
    }

    @Test
    fun `cancelling the caller stops the sale on the terminal`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        val sale = launch { terminal.sale(amount) }
        runCurrent()
        sale.cancel()
        sale.join()

        assertEquals(1, fake.calls.count { it == "stopCurrentTransaction" })
    }

    @Test
    fun `a sale that ends does not stop the transaction`() = runTest {
        val terminal = newTerminal()
        terminal.connect()
        fake.duringSale = {
            fire<Events.EndOfTransaction> { it.endOfTransaction(approvedResult(SALE_REFERENCE), DEVICE) }
        }

        terminal.sale(amount)

        assertFalse(fake.calls.contains("stopCurrentTransaction"))
    }

    @Test
    fun `sale result delivered from another thread resolves the sale`() = runBlocking {
        val terminal = newTerminal()
        terminal.connect()
        fake.duringSale = {
            // Like the SDK's executor: a separate thread, a moment after sale() returns.
            thread {
                Thread.sleep(50)
                fire<Events.EndOfTransaction> { it.endOfTransaction(approvedResult(SALE_REFERENCE), DEVICE) }
            }
        }

        val outcome = withTimeout(5.seconds) { terminal.sale(amount) }

        assertTrue(outcome is SaleOutcome.ApprovedOffline)
    }

    @Test
    fun `cancel sale asks the SDK to stop the current transaction`() = runTest {
        val terminal = newTerminal()

        assertTrue(terminal.cancelSale())
        assertTrue(fake.calls.contains("stopCurrentTransaction"))
    }

    // --- stored sales and forwarding ---------------------------------------------------------

    @Test
    fun `stored on terminal returns references and the stored total, not the batch limit`() = runTest {
        val terminal = newTerminal()
        terminal.connect()
        val references = listOf(TransactionReference(SALE_REFERENCE), TransactionReference(OTHER_REFERENCE))

        val summary = async { terminal.storedOnTerminal() }
        runCurrent()
        fake.fire<Events.StoredTransactionsEvent> {
            it.transactionsStoredList(references, SdkCurrency.USD, BigInteger.valueOf(100_000), BigInteger.valueOf(8450))
        }

        assertEquals(
            StoredSummary(listOf(SaleReference(SALE_REFERENCE), SaleReference(OTHER_REFERENCE)), Money(8450, usd)),
            summary.await(),
        )
    }

    @Test
    fun `stored on terminal is null when the SDK refuses or does not answer`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        assertNull(terminal.storedOnTerminal())
        assertEquals(15_000L, testScheduler.currentTime)

        fake.accepts = false
        assertNull(terminal.storedOnTerminal())
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test
    fun `forwarding emits started, sent and finished in order`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        terminal.forwarding.test {
            assertTrue(terminal.forward())
            fake.fire<Events.StoredTransactionsForwardingStatus> {
                it.transactionsToForward(3)
                it.transactionForwarded(SALE_REFERENCE, 1, 3)
                it.transactionForwardingFinished(ForwardingTransactionStatus.Success(3))
            }

            assertEquals(ForwardingEvent.Started(3), awaitItem())
            assertEquals(ForwardingEvent.Sent(SaleReference(SALE_REFERENCE), 1, 3), awaitItem())
            assertEquals(ForwardingEvent.Finished(ForwardingResult.AllSent(3)), awaitItem())
        }
        assertTrue(fake.calls.contains("forwardStoredTransactions"))
    }

    @Test
    fun `forwarding reports when there is nothing to send`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        terminal.forwarding.test {
            fake.fire<Events.StoredTransactionsForwardingStatus> { it.noTransactionsToForward() }
            assertEquals(ForwardingEvent.NothingToSend, awaitItem())
        }
    }

    @Test
    fun `forward is false when the SDK refuses`() = runTest {
        val terminal = newTerminal()
        fake.accepts = false

        assertFalse(terminal.forward())
    }

    // --- processing status -------------------------------------------------------------------

    @Test
    fun `processing status returns the status for the asked reference`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        val status = async { terminal.processingStatus(SaleReference(SALE_REFERENCE)) }
        runCurrent()
        fake.fire<Events.GetSafTransactionStatusEvent> {
            it.safTransactionStatus(TransactionReference(OTHER_REFERENCE), SafTransactionStatus.FAILED)
            it.safTransactionStatus(TransactionReference(SALE_REFERENCE), SafTransactionStatus.SUCCEEDED)
        }

        assertEquals(ProcessingStatus.SUCCEEDED, status.await())
        assertTrue(fake.calls.contains("getTransactionProcessingStatus($SALE_REFERENCE)"))
    }

    @Test
    fun `processing status is null on the SDK unexpected status error`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        val status = async { terminal.processingStatus(SaleReference(SALE_REFERENCE)) }
        runCurrent()
        fake.fire<Events.GetSafTransactionStatusEvent> { it.unexpectedSafTrasnsactionStatusError() }

        assertNull(status.await())
    }

    @Test
    fun `processing status is null after 20 seconds without an answer`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        assertNull(terminal.processingStatus(SaleReference(SALE_REFERENCE)))
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test
    fun `processing status is null for a reference the SDK cannot accept`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        assertNull(terminal.processingStatus(SaleReference("not-a-uuid")))
        assertFalse(fake.calls.any { it.startsWith("getTransactionProcessingStatus") })
    }

    // --- delete ------------------------------------------------------------------------------

    @Test
    fun `delete reports deleted when the SDK confirms`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        val result = async { terminal.delete(SaleReference(SALE_REFERENCE)) }
        runCurrent()
        fake.fire<Events.DeleteStoredTransactionEvent> {
            it.storedTransactionDeletedSuccessfully(TransactionReference(SALE_REFERENCE))
        }

        assertEquals(DeleteResult.DELETED, result.await())
    }

    @Test
    fun `delete reports not on terminal when the SDK has no such sale`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        val result = async { terminal.delete(SaleReference(SALE_REFERENCE)) }
        runCurrent()
        fake.fire<Events.DeleteStoredTransactionEvent> {
            it.errorDeletingStoredTransaction(
                TransactionReference(SALE_REFERENCE),
                TransactionDeletionFailed.NO_EXISTING_TRANSACTION,
            )
        }

        assertEquals(DeleteResult.NOT_ON_TERMINAL, result.await())
    }

    @Test
    fun `delete reports error after 15 seconds without an answer`() = runTest {
        val terminal = newTerminal()
        terminal.connect()

        assertEquals(DeleteResult.ERROR, terminal.delete(SaleReference(SALE_REFERENCE)))
        assertEquals(15_000L, testScheduler.currentTime)
    }

    // --- printing ----------------------------------------------------------------------------

    @Test
    fun `print receipt passes the receipt to the SDK`() = runTest {
        val terminal = newTerminal()

        assertTrue(terminal.printReceipt("receipt"))
        assertTrue(fake.calls.contains("printReceipt(receipt)"))
    }

    private fun approvedResult(reference: String) = TransactionResult().apply {
        finStatus = FinancialStatus.AUTHORISED_DEFERRED
        transactionReference = reference
        maskedCardNumber = "************4242"
    }

    private fun failedResult(message: String, reference: String) = TransactionResult().apply {
        finStatus = FinancialStatus.FAILED
        transactionReference = reference
        statusMessage = message
        errorMessage = message
    }
}
