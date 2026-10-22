package com.handpoint.safclient.ui.sale

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
    val canTryAgain: Boolean,
)

enum class ResultKind { APPROVED, DECLINED, UNKNOWN }

/**
 * Merchant-facing copy for a sale outcome. [currency] is the SAF currency from settings, the one
 * the sale was taken in; it is named when the terminal refuses it.
 *
 * An unknown outcome offers no Try again: the terminal may already hold the sale, and a second
 * charge could take the money twice.
 */
fun resultCopy(outcome: SaleOutcome, currency: Currency): ResultCopy = when (outcome) {
    is SaleOutcome.ApprovedOffline -> ResultCopy(
        kind = ResultKind.APPROVED,
        title = "Approved offline",
        body = "Stored securely on this terminal. It will be charged when you send it.",
        canPrint = true,
        canTryAgain = false,
    )
    is SaleOutcome.Declined -> ResultCopy(
        kind = ResultKind.DECLINED,
        title = "Sale declined",
        body = outcome.reason.message(currency),
        canPrint = false,
        canTryAgain = true,
    )
    is SaleOutcome.Failed -> if (outcome.message == StoreAndForwardTerminal.OUTCOME_UNKNOWN) {
        ResultCopy(
            kind = ResultKind.UNKNOWN,
            title = "Couldn't confirm the sale",
            body = "Check Home before charging again — the sale may already be stored on this terminal.",
            canPrint = false,
            canTryAgain = false,
        )
    } else {
        ResultCopy(
            kind = ResultKind.DECLINED,
            title = "Sale not completed",
            body = "The sale couldn't be completed.",
            canPrint = false,
            canTryAgain = true,
        )
    }
    // The view model turns a cancelled sale back into amount entry; kept for completeness.
    SaleOutcome.Cancelled -> ResultCopy(
        kind = ResultKind.DECLINED,
        title = "Sale cancelled",
        body = "No sale was stored.",
        canPrint = false,
        canTryAgain = true,
    )
}

private fun DeclineReason.message(currency: Currency): String = when (this) {
    DeclineReason.PREPAID_CARD -> "Prepaid cards can't be used offline. Ask for another card."
    DeclineReason.CARD_LIMIT_REACHED -> "This card has reached its offline limit. Ask for another card."
    DeclineReason.TERMINAL_LIMIT_REACHED ->
        "This terminal has reached its offline limit. Send stored sales, then try again."
    DeclineReason.CARD_EXPIRED -> "The card's chip has expired. Ask for another card."
    DeclineReason.CURRENCY_NOT_ACCEPTED ->
        "This terminal doesn't take offline sales in ${currency.currencyCode}. Check the currency in Settings."
    DeclineReason.CARD_NOT_ACCEPTED -> "This card isn't accepted."
    DeclineReason.OTHER -> "The sale couldn't be completed."
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
