package com.handpoint.safclient.data.handpoint

import com.handpoint.api.shared.CardEntryType
import com.handpoint.api.shared.ConnectionStatus
import com.handpoint.api.shared.FinancialStatus
import com.handpoint.api.shared.PaymentScenario
import com.handpoint.api.shared.SafTransactionStatus
import com.handpoint.api.shared.StatusInfo
import com.handpoint.api.shared.TransactionResult
import com.handpoint.api.shared.storeandforward.ForwardingTransactionErrorReason
import com.handpoint.api.shared.storeandforward.ForwardingTransactionStatus
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.testutil.FakeSafApi.Companion.SALE_REFERENCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Currency

class SdkMappersTest {

    private val usd = Currency.getInstance("USD")
    private val amount = Money(8450, usd)
    private val now = Instant.parse("2026-10-22T10:15:30Z")

    @Test
    fun `decline codes map to decline reasons`() {
        assertEquals(DeclineReason.PREPAID_CARD, "PREPAID_CARD".toDeclineReason())
        assertEquals(DeclineReason.CARD_LIMIT_REACHED, "EXCEEDED_MAX_AMOUNT_BY_PAN".toDeclineReason())
        assertEquals(DeclineReason.TERMINAL_LIMIT_REACHED, "EXCEEDED_MAX_AMOUNT".toDeclineReason())
        assertEquals(DeclineReason.CARD_EXPIRED, "EXPIRED_CARD".toDeclineReason())
        assertEquals(DeclineReason.CURRENCY_NOT_ACCEPTED, "TEXT_ERROR_CURRENCY".toDeclineReason())
        assertEquals(DeclineReason.CURRENCY_NOT_ACCEPTED, "Invalid currency for this terminal".toDeclineReason())
        assertEquals(DeclineReason.CARD_NOT_ACCEPTED, "TEXT_CARD_NOT_SUPPORTED".toDeclineReason())
        assertEquals(DeclineReason.OTHER, "TEXT_DEFAULT_ERROR_MSG".toDeclineReason())
        assertEquals(DeclineReason.OTHER, "".toDeclineReason())
    }

    @Test
    fun `deferred authorisation is an offline approval stored on the terminal`() {
        val result = transactionResult(FinancialStatus.AUTHORISED_DEFERRED).apply {
            maskedCardNumber = "************4242"
            cardEntryType = CardEntryType.ICC
            paymentScenario = PaymentScenario.CHIP
            merchantReceipt = "merchant copy"
            customerReceipt = "customer copy"
        }

        val outcome = result.toOutcome(amount, now)

        assertEquals(
            SaleOutcome.ApprovedOffline(
                Sale(
                    reference = SaleReference(SALE_REFERENCE),
                    amount = amount,
                    maskedCard = "•••• 4242",
                    entryMode = EntryMode.CHIP,
                    approvedAt = now,
                    sentAt = null,
                    status = ProcessingStatus.STORED,
                    statusCheckedAt = null,
                    merchantReceipt = "merchant copy",
                    customerReceipt = "customer copy",
                ),
            ),
            outcome,
        )
    }

    @Test
    fun `approval without card number or receipts keeps them empty`() {
        val outcome = transactionResult(FinancialStatus.AUTHORISED_DEFERRED).toOutcome(amount, now)

        val sale = (outcome as SaleOutcome.ApprovedOffline).sale
        assertNull(sale.maskedCard)
        assertNull(sale.merchantReceipt)
        assertNull(sale.customerReceipt)
    }

    @Test
    fun `cancelled, failed, declined and other statuses map to their outcomes`() {
        assertEquals(SaleOutcome.Cancelled, transactionResult(FinancialStatus.CANCELLED).toOutcome(amount, now))
        assertEquals(
            SaleOutcome.Declined(DeclineReason.PREPAID_CARD),
            transactionResult(FinancialStatus.FAILED, errorMessage = "PREPAID_CARD").toOutcome(amount, now),
        )
        assertEquals(
            SaleOutcome.Declined(DeclineReason.CARD_EXPIRED),
            transactionResult(FinancialStatus.DECLINED, errorMessage = "EXPIRED_CARD").toOutcome(amount, now),
        )
        assertEquals(
            SaleOutcome.Failed("boom"),
            transactionResult(FinancialStatus.UNDEFINED, errorMessage = "boom").toOutcome(amount, now),
        )
    }

    @Test
    fun `failed with an unknown key is a failure, declined with an unknown key is a decline`() {
        val failed = transactionResult(FinancialStatus.FAILED, errorMessage = "TEXT_DEFAULT_ERROR_MSG")
            .toOutcome(amount, now)
        assertTrue(failed is SaleOutcome.Failed)

        assertEquals(
            SaleOutcome.Declined(DeclineReason.OTHER),
            transactionResult(FinancialStatus.DECLINED, errorMessage = "DO_NOT_HONOUR").toOutcome(amount, now),
        )
    }

    @Test
    fun `entry mode comes from the payment scenario first, then the card entry type`() {
        fun entryMode(scenario: PaymentScenario, entry: CardEntryType) =
            transactionResult(FinancialStatus.AUTHORISED_DEFERRED)
                .apply { paymentScenario = scenario; cardEntryType = entry }
                .toEntryMode()

        assertEquals(EntryMode.CONTACTLESS, entryMode(PaymentScenario.CHIPCONTACTLESS, CardEntryType.ICC))
        assertEquals(EntryMode.CONTACTLESS, entryMode(PaymentScenario.MAGSTRIPECONTACTLESS, CardEntryType.MSR))
        assertEquals(EntryMode.CHIP, entryMode(PaymentScenario.CHIP, CardEntryType.ICC))
        assertEquals(EntryMode.SWIPE, entryMode(PaymentScenario.MAGSTRIPE, CardEntryType.MSR))
        assertEquals(EntryMode.MANUAL, entryMode(PaymentScenario.MOTO, CardEntryType.CNP))
        assertEquals(EntryMode.UNKNOWN, entryMode(PaymentScenario.UNKNOWN, CardEntryType.UNDEFINED))
    }

    @Test
    fun `card prompts map to card steps and other statuses are dropped`() {
        assertEquals(CardStep.Kind.PRESENT_CARD, StatusInfo.Status.WaitingForCard.toCardStepKind())
        assertEquals(CardStep.Kind.CARD_READ, StatusInfo.Status.CardInserted.toCardStepKind())
        assertEquals(CardStep.Kind.CARD_READ, StatusInfo.Status.CardTapped.toCardStepKind())
        assertEquals(CardStep.Kind.CARD_READ, StatusInfo.Status.IccCardSwiped.toCardStepKind())
        assertEquals(CardStep.Kind.ENTER_PIN, StatusInfo.Status.PinInput.toCardStepKind())
        assertEquals(CardStep.Kind.ENTER_PIN, StatusInfo.Status.OfflinePinAttempt.toCardStepKind())
        assertEquals(CardStep.Kind.ENTER_PIN, StatusInfo.Status.OfflinePinLastAttempt.toCardStepKind())
        assertEquals(CardStep.Kind.REMOVE_CARD, StatusInfo.Status.RemoveCard.toCardStepKind())
        assertEquals(CardStep.Kind.REMOVE_CARD, StatusInfo.Status.WaitingForCardRemoval.toCardStepKind())
        assertEquals(CardStep.Kind.PROCESSING, StatusInfo.Status.WaitingHostConnect.toCardStepKind())
        assertEquals(CardStep.Kind.PROCESSING, StatusInfo.Status.WaitingHostSend.toCardStepKind())
        assertEquals(CardStep.Kind.PROCESSING, StatusInfo.Status.WaitingHostReceive.toCardStepKind())
        assertEquals(CardStep.Kind.PROCESSING, StatusInfo.Status.WaitingHostDisconnect.toCardStepKind())
        assertEquals(CardStep.Kind.PROCESSING, StatusInfo.Status.WaitingHostPostSend.toCardStepKind())
        assertEquals(CardStep.Kind.PROCESSING, StatusInfo.Status.WaitingHostPostReceive.toCardStepKind())
        assertEquals(CardStep.Kind.PROCESSING, StatusInfo.Status.ApplicationSelection.toCardStepKind())
        assertEquals(CardStep.Kind.PROCESSING, StatusInfo.Status.AmountValidation.toCardStepKind())
        assertNull(StatusInfo.Status.Undefined.toCardStepKind())
        assertNull(StatusInfo.Status.PrintingMerchantReceipt.toCardStepKind())
    }

    @Test
    fun `card step carries whether cancel is allowed`() {
        val info = StatusInfo(isCancelAllowed = true, status = StatusInfo.Status.WaitingForCard)

        assertEquals(CardStep(CardStep.Kind.PRESENT_CARD, cancelAllowed = true), info.toCardStep())
        assertNull(StatusInfo(status = StatusInfo.Status.Success).toCardStep())
    }

    @Test
    fun `connection statuses map to terminal connection`() {
        assertEquals(TerminalConnection.CONNECTED, ConnectionStatus.Connected.toTerminalConnection())
        assertEquals(TerminalConnection.CONNECTING, ConnectionStatus.Connecting.toTerminalConnection())
        assertEquals(TerminalConnection.NOT_CONFIGURED, ConnectionStatus.NotConfigured.toTerminalConnection())
        assertEquals(TerminalConnection.DISCONNECTED, ConnectionStatus.Disconnected.toTerminalConnection())
        assertEquals(TerminalConnection.DISCONNECTED, ConnectionStatus.Disconnecting.toTerminalConnection())
        assertEquals(TerminalConnection.DISCONNECTED, ConnectionStatus.CloudConnected.toTerminalConnection())
    }

    @Test
    fun `forwarding statuses map field by field`() {
        assertEquals(
            ForwardingResult.AllSent(3),
            ForwardingTransactionStatus.Success(3).toForwardingResult(),
        )
        assertEquals(
            ForwardingResult.Partial(2, 0, 1, 3, ForwardingError.NETWORK),
            ForwardingTransactionStatus.PartialSuccess(2, 0, 1, 3, ForwardingTransactionErrorReason.NETWORK_ERROR)
                .toForwardingResult(),
        )
        assertEquals(
            ForwardingResult.NoneSent(ForwardingError.CORRUPTED, corrupted = 1, failed = 2, total = 3),
            ForwardingTransactionStatus.Failed(ForwardingTransactionErrorReason.CORRUPTED_TRANSACTION, 1, 2, 3)
                .toForwardingResult(),
        )
        assertEquals(
            ForwardingError.FAILED,
            ForwardingTransactionErrorReason.FAILED_FORWARDING_TRANSACTION.toForwardingError(),
        )
    }

    @Test
    fun `every SDK processing status maps to the status of the same name`() {
        SafTransactionStatus.entries.forEach { sdkStatus ->
            assertEquals(sdkStatus.name, sdkStatus.toProcessingStatus().name)
        }
    }

    @Test
    fun `decline reason comes from the untranslated key when the message is translated`() {
        // What the SDK's own decline path does: same key in both, errorMessage then translated.
        val declined = transactionResult(FinancialStatus.FAILED, errorMessage = "EXCEEDED_MAX_AMOUNT_BY_PAN")

        assertEquals(SaleOutcome.Declined(DeclineReason.CARD_LIMIT_REACHED), declined.toOutcome(amount, now))
    }

    /** Builds a result the way the SDK's TransactionResultFactory does. */
    private fun transactionResult(status: FinancialStatus, errorMessage: String = "") =
        TransactionResult().apply {
            finStatus = status
            transactionReference = SALE_REFERENCE
            statusMessage = errorMessage
            this.errorMessage = errorMessage
        }
}
