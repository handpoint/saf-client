package com.handpoint.safclient.data.handpoint

import com.handpoint.api.shared.CardEntryType
import com.handpoint.api.shared.ConnectionStatus
import com.handpoint.api.shared.FinancialStatus
import com.handpoint.api.shared.SafTransactionStatus
import com.handpoint.api.shared.StatusInfo
import com.handpoint.api.shared.TransactionResult
import com.handpoint.api.shared.storeandforward.ForwardingTransactionErrorReason
import com.handpoint.api.shared.storeandforward.ForwardingTransactionStatus
import com.handpoint.api.shared.storeandforward.TransactionReference
import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.TerminalConnection
import java.time.Instant
import com.handpoint.api.shared.Currency as SdkCurrency
import java.util.Currency as JavaCurrency

// SDK types -> domain types. Pure functions, no state, no threads.

// --- Sale result ---------------------------------------------------------------------------

/**
 * Turns the SDK's end of transaction into a sale outcome.
 *
 * [amount] is the amount the app asked for; [now] is the approval time.
 */
internal fun TransactionResult.toOutcome(amount: Money, now: Instant): SaleOutcome =
    when (finStatus) {
        // Store and Forward approves offline: the sale is stored on the terminal and
        // forwarded to Handpoint later.
        FinancialStatus.AUTHORISED_DEFERRED -> SaleOutcome.ApprovedOffline(
            Sale(
                reference = SaleReference(transactionReference),
                amount = amount,
                maskedCard = maskedCardNumber.toMaskedCard(),
                entryMode = toEntryMode(),
                approvedAt = now,
                sentAt = null,
                status = ProcessingStatus.STORED,
                statusCheckedAt = null,
                merchantReceipt = merchantReceipt.ifBlank { null },
                customerReceipt = customerReceipt.ifBlank { null },
            ),
        )

        FinancialStatus.CANCELLED -> SaleOutcome.Cancelled

        // Offline declines (card limit, prepaid card, ...) arrive as FAILED with a known key.
        // A FAILED result with any other key is an error, not a decline.
        FinancialStatus.FAILED, FinancialStatus.DECLINED -> {
            val reason = declineReason()
            if (reason != DeclineReason.OTHER || finStatus == FinancialStatus.DECLINED) {
                SaleOutcome.Declined(reason)
            } else {
                SaleOutcome.Failed(errorMessage.ifBlank { "The sale failed" })
            }
        }

        // Any other status (for example an online AUTHORISED) is not a result this flow
        // expects: the terminal may still have stored the sale, so treat the outcome as unknown
        // and let the caller reconcile before the merchant charges again.
        else -> SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN)
    }

/**
 * Turns the SDK's end of an online transaction into a sale outcome.
 *
 * Only AUTHORISED is an approval: the card has been charged, so the sale is recorded as
 * SUCCEEDED, with nothing left to send. A result this flow does not expect (for example a
 * partial approval) is an unknown outcome: the card may have been charged, so the merchant must
 * not simply charge again.
 */
internal fun TransactionResult.toOnlineOutcome(amount: Money, now: Instant): SaleOutcome =
    when (finStatus) {
        FinancialStatus.AUTHORISED -> SaleOutcome.ApprovedOnline(
            Sale(
                reference = SaleReference(transactionReference),
                amount = amount,
                maskedCard = maskedCardNumber.toMaskedCard(),
                entryMode = toEntryMode(),
                approvedAt = now,
                sentAt = null,
                status = ProcessingStatus.SUCCEEDED,
                statusCheckedAt = now,
                merchantReceipt = merchantReceipt.ifBlank { null },
                customerReceipt = customerReceipt.ifBlank { null },
                channel = SaleChannel.ONLINE,
            ),
        )

        FinancialStatus.DECLINED -> SaleOutcome.DeclinedOnline(errorMessage.ifBlank { "Declined" })
        FinancialStatus.CANCELLED -> SaleOutcome.Cancelled
        FinancialStatus.FAILED -> SaleOutcome.Failed(errorMessage.ifBlank { "The sale failed" })
        else -> SaleOutcome.Failed(OnlineTerminal.OUTCOME_UNKNOWN)
    }

/**
 * The SDK translates `errorMessage` into the card or device language ("PREPAID_CARD" becomes
 * "Prepaid card"), but keeps the untranslated key in `_statusMessage`. Match the key first,
 * then the message (some SDK checks put plain English text there, e.g. "Invalid currency: ...").
 */
private fun TransactionResult.declineReason(): DeclineReason {
    val fromKey = _statusMessage.toDeclineReason()
    return if (fromKey != DeclineReason.OTHER) fromKey else errorMessage.toDeclineReason()
}

/** Maps an SDK decline code to the reason the app explains to the merchant. */
internal fun String.toDeclineReason(): DeclineReason = when {
    this == "PREPAID_CARD" -> DeclineReason.PREPAID_CARD
    this == "EXCEEDED_MAX_AMOUNT_BY_PAN" -> DeclineReason.CARD_LIMIT_REACHED
    this == "EXCEEDED_MAX_AMOUNT" -> DeclineReason.TERMINAL_LIMIT_REACHED
    this == "EXPIRED_CARD" -> DeclineReason.CARD_EXPIRED
    this == "TEXT_ERROR_CURRENCY" || startsWith("Invalid currency") -> DeclineReason.CURRENCY_NOT_ACCEPTED
    this == "TEXT_CARD_NOT_SUPPORTED" -> DeclineReason.CARD_NOT_ACCEPTED
    else -> DeclineReason.OTHER
}

/** `"************4242"` becomes `"•••• 4242"`. No card number gives `null`. */
private fun String.toMaskedCard(): String? =
    if (length < 4) null else "•••• " + takeLast(4)

internal fun TransactionResult.toEntryMode(): EntryMode = when {
    // CONTACTLESS, CHIPCONTACTLESS and MAGSTRIPECONTACTLESS are all taps.
    paymentScenario.name.contains("CONTACTLESS") -> EntryMode.CONTACTLESS
    cardEntryType == CardEntryType.ICC -> EntryMode.CHIP
    cardEntryType == CardEntryType.MSR -> EntryMode.SWIPE
    cardEntryType == CardEntryType.CNP -> EntryMode.MANUAL
    else -> EntryMode.UNKNOWN
}

// --- Card prompts during a sale ------------------------------------------------------------

/** A card prompt for the Charge screen, or `null` for statuses the app does not show. */
internal fun StatusInfo.toCardStep(): CardStep? =
    status.toCardStepKind()?.let { kind -> CardStep(kind, cancelAllowed = isCancelAllowed) }

internal fun StatusInfo.Status.toCardStepKind(): CardStep.Kind? = when (this) {
    StatusInfo.Status.WaitingForCard -> CardStep.Kind.PRESENT_CARD

    StatusInfo.Status.CardInserted,
    StatusInfo.Status.CardTapped,
    StatusInfo.Status.IccCardSwiped,
    -> CardStep.Kind.CARD_READ

    StatusInfo.Status.PinInput,
    StatusInfo.Status.OfflinePinAttempt,
    StatusInfo.Status.OfflinePinLastAttempt,
    -> CardStep.Kind.ENTER_PIN

    StatusInfo.Status.RemoveCard,
    StatusInfo.Status.WaitingForCardRemoval,
    -> CardStep.Kind.REMOVE_CARD

    StatusInfo.Status.WaitingHostConnect,
    StatusInfo.Status.WaitingHostSend,
    StatusInfo.Status.WaitingHostReceive,
    StatusInfo.Status.WaitingHostDisconnect,
    StatusInfo.Status.WaitingHostPostSend,
    StatusInfo.Status.WaitingHostPostReceive,
    StatusInfo.Status.ApplicationSelection,
    StatusInfo.Status.AmountValidation,
    -> CardStep.Kind.PROCESSING

    else -> null
}

// --- Connection ----------------------------------------------------------------------------

internal fun ConnectionStatus.toTerminalConnection(): TerminalConnection = when (this) {
    ConnectionStatus.Connected -> TerminalConnection.CONNECTED
    ConnectionStatus.Connecting -> TerminalConnection.CONNECTING
    ConnectionStatus.NotConfigured -> TerminalConnection.NOT_CONFIGURED
    else -> TerminalConnection.DISCONNECTED
}

// --- Forwarding ----------------------------------------------------------------------------

internal fun ForwardingTransactionStatus.toForwardingResult(): ForwardingResult = when (this) {
    is ForwardingTransactionStatus.Success -> ForwardingResult.AllSent(
        sent = successfullyForwardedTransaction,
    )

    is ForwardingTransactionStatus.PartialSuccess -> ForwardingResult.Partial(
        sent = successfullyForwardedTransaction,
        corrupted = corruptedTransactions,
        failed = failedForwardedTransactions,
        total = totalStoredTransactions,
        error = forwardingTransactionErrorReason.toForwardingError(),
    )

    is ForwardingTransactionStatus.Failed -> ForwardingResult.NoneSent(
        error = forwardingTransactionErrorReason.toForwardingError(),
        corrupted = corruptedTransactions,
        failed = failedForwardedTransactions,
        total = totalStoredTransactions,
    )
}

internal fun ForwardingTransactionErrorReason.toForwardingError(): ForwardingError = when (this) {
    ForwardingTransactionErrorReason.NETWORK_ERROR -> ForwardingError.NETWORK
    ForwardingTransactionErrorReason.CORRUPTED_TRANSACTION -> ForwardingError.CORRUPTED
    ForwardingTransactionErrorReason.FAILED_FORWARDING_TRANSACTION -> ForwardingError.FAILED
}

// --- Processing status ---------------------------------------------------------------------

internal fun SafTransactionStatus.toProcessingStatus(): ProcessingStatus = when (this) {
    SafTransactionStatus.STORED -> ProcessingStatus.STORED
    SafTransactionStatus.IN_PROGRESS -> ProcessingStatus.IN_PROGRESS
    SafTransactionStatus.SUCCEEDED -> ProcessingStatus.SUCCEEDED
    SafTransactionStatus.FAILED -> ProcessingStatus.FAILED
    SafTransactionStatus.CAPTURED -> ProcessingStatus.CAPTURED
    SafTransactionStatus.REFUNDED -> ProcessingStatus.REFUNDED
    SafTransactionStatus.CANCELLED -> ProcessingStatus.CANCELLED
    SafTransactionStatus.NOT_FOUND -> ProcessingStatus.NOT_FOUND
}

// --- References and money ------------------------------------------------------------------

internal fun TransactionReference.toSaleReference(): SaleReference = SaleReference(value())

/** The SDK only accepts UUID references; anything else gives `null`. */
internal fun SaleReference.toTransactionReferenceOrNull(): TransactionReference? =
    try {
        TransactionReference(value)
    } catch (e: IllegalArgumentException) {
        null
    }

internal fun JavaCurrency.toSdkCurrency(): SdkCurrency = SdkCurrency.parse(currencyCode)

/** `null` for the SDK's `Currency.Unknown`. */
internal fun SdkCurrency.toJavaCurrencyOrNull(): JavaCurrency? =
    if (this == SdkCurrency.Unknown) null else JavaCurrency.getInstance(alpha)
