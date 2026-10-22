package com.handpoint.safclient.ui.sale

import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.SaleOutcome
import java.util.Currency

/** What the result screen says about an outcome, and which actions it offers. */
data class ResultCopy(
    val kind: ResultKind,
    val title: String,
    val body: String,
    val canPrint: Boolean,
    /** Charge again with the same amount: another card may work. */
    val canTryAgain: Boolean,
    /** Back to the amount screen: the amount itself was refused, so the same amount would be too. */
    val canChangeAmount: Boolean = false,
)

enum class ResultKind { APPROVED, DECLINED, UNKNOWN }

/**
 * Merchant-facing copy for a sale outcome. [currency] is the SAF currency from settings, the one
 * the sale was taken in; it is named when the terminal refuses it.
 *
 * An unknown outcome offers no Try again: the terminal may already hold the sale (offline), or
 * the card may already be charged (online), and a second charge could take the money twice.
 *
 * A decline shows the SDK's own message when it has one (it states the limit as money, in the
 * card's language) under the app's title; the app's copy is the fallback. A decline of the
 * amount (over the per-sale limit, invalid, over the terminal's limit) offers Change amount
 * instead of Try again; a currency decline offers only Done.
 */
fun resultCopy(outcome: SaleOutcome, currency: Currency): ResultCopy = when (outcome) {
    is SaleOutcome.ApprovedOffline -> ResultCopy(
        kind = ResultKind.APPROVED,
        title = "Approved offline",
        body = "Stored securely on this terminal. It will be charged when you send it.",
        canPrint = true,
        canTryAgain = false,
    )
    is SaleOutcome.ApprovedOnline -> ResultCopy(
        kind = ResultKind.APPROVED,
        title = "Approved",
        body = "The card has been charged.",
        canPrint = true,
        canTryAgain = false,
    )
    is SaleOutcome.DeclinedOnline -> ResultCopy(
        kind = ResultKind.DECLINED,
        title = "Declined",
        body = outcome.message.ifBlank { outcome.reason.onlineMessage(currency) },
        canPrint = false,
        canTryAgain = true,
    )
    is SaleOutcome.Declined -> {
        val reason = outcome.reason
        val amountRefused = reason in AMOUNT_REFUSED
        ResultCopy(
            kind = ResultKind.DECLINED,
            title = reason.title(),
            body = outcome.message.ifBlank { reason.message(currency) },
            canPrint = false,
            canTryAgain = !amountRefused && reason != DeclineReason.CURRENCY_NOT_ACCEPTED,
            canChangeAmount = amountRefused,
        )
    }
    is SaleOutcome.Failed -> when (outcome.message) {
        StoreAndForwardTerminal.OUTCOME_UNKNOWN -> ResultCopy(
            kind = ResultKind.UNKNOWN,
            title = "Couldn't confirm the sale",
            body = "Check Home before charging again — the sale may already be stored on this terminal.",
            canPrint = false,
            canTryAgain = false,
        )
        OnlineTerminal.OUTCOME_UNKNOWN -> ResultCopy(
            kind = ResultKind.UNKNOWN,
            title = "Couldn't confirm the sale",
            body = "The card may have been charged. Check the transaction with Handpoint before charging again.",
            canPrint = false,
            canTryAgain = false,
        )
        else -> ResultCopy(
            kind = ResultKind.DECLINED,
            title = "Sale not completed",
            body = outcome.message.takeUnless { it.isBlank() || it.matches(RAW_KEY) }
                ?: "The sale couldn't be completed.",
            canPrint = false,
            canTryAgain = true,
        )
    }
    // The view model turns a cancelled sale back into amount entry; kept for completeness.
    SaleOutcome.Cancelled -> ResultCopy(
        kind = ResultKind.DECLINED,
        title = "Sale cancelled",
        body = "The card was not charged.",
        canPrint = false,
        canTryAgain = true,
    )
}

/** Declines of the amount itself: the same amount fails again, whatever the card. */
private val AMOUNT_REFUSED = setOf(
    DeclineReason.OVER_SALE_LIMIT,
    DeclineReason.INVALID_AMOUNT,
    DeclineReason.TERMINAL_LIMIT_REACHED,
)

/** An untranslated SDK key such as `TEXT_ERROR_AMOUNT`: never shown to the merchant. */
private val RAW_KEY = Regex("[A-Z0-9_]+")

private fun DeclineReason.title(): String = when (this) {
    DeclineReason.OVER_SALE_LIMIT -> "Over the offline limit"
    DeclineReason.TERMINAL_LIMIT_REACHED -> "Terminal limit reached"
    DeclineReason.CARD_LIMIT_REACHED -> "Card limit reached"
    DeclineReason.INVALID_AMOUNT -> "Amount not accepted"
    DeclineReason.CURRENCY_NOT_ACCEPTED -> "Currency not accepted"
    else -> "Sale declined"
}

private fun DeclineReason.message(currency: Currency): String = when (this) {
    DeclineReason.OVER_SALE_LIMIT -> "This sale is over the terminal's offline limit per sale. Change the amount."
    DeclineReason.INVALID_AMOUNT -> "This amount can't be taken. Change the amount."
    DeclineReason.DECLINED -> "The card was declined. Ask for another way to pay."
    DeclineReason.PREPAID_CARD -> "Prepaid cards can't be used offline. Ask for another card."
    DeclineReason.CARD_LIMIT_REACHED -> "This card has reached its offline limit. Ask for another card."
    DeclineReason.TERMINAL_LIMIT_REACHED ->
        "This terminal has reached its offline limit. Send stored sales, then try again."
    DeclineReason.CARD_EXPIRED -> "The card's chip has expired. Ask for another card."
    DeclineReason.CURRENCY_NOT_ACCEPTED ->
        "This terminal doesn't take offline sales in ${currency.currencyCode}. Ask Handpoint to check the terminal's currency."
    DeclineReason.CARD_NOT_ACCEPTED -> "This card isn't accepted."
    DeclineReason.OTHER -> "The sale couldn't be completed."
}

/** An online decline in the app's words, when the SDK gave no readable message. */
private fun DeclineReason.onlineMessage(currency: Currency): String = when (this) {
    DeclineReason.CARD_EXPIRED -> "The card has expired. Ask for another card."
    DeclineReason.CARD_NOT_ACCEPTED -> "This card isn't accepted. Ask for another way to pay."
    DeclineReason.CURRENCY_NOT_ACCEPTED ->
        "This terminal doesn't take sales in ${currency.currencyCode}. Ask Handpoint to check the terminal's currency."
    DeclineReason.INVALID_AMOUNT -> "This amount can't be taken. Change the amount."
    DeclineReason.PREPAID_CARD,
    DeclineReason.CARD_LIMIT_REACHED,
    DeclineReason.TERMINAL_LIMIT_REACHED,
    DeclineReason.OVER_SALE_LIMIT,
    DeclineReason.DECLINED,
    DeclineReason.OTHER,
    -> "The card was declined. Ask for another way to pay."
}

/** What the terminal is waiting for, in merchant words. `null` before the first prompt. */
fun CardStep?.message(): String = when (this?.kind) {
    null -> "Starting…"
    CardStep.Kind.PRESENT_CARD -> "Tap, insert or swipe"
    CardStep.Kind.CARD_READ -> "Card read"
    CardStep.Kind.ENTER_PIN -> "Customer is entering PIN"
    CardStep.Kind.PROCESSING -> "Processing…"
    CardStep.Kind.REMOVE_CARD -> "Remove card"
}
