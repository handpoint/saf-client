package com.handpoint.safclient.domain.model

sealed interface SaleOutcome {
    /** Store and Forward approved the sale: it is stored on the terminal and charged once sent. */
    data class ApprovedOffline(val sale: Sale) : SaleOutcome

    /** Handpoint approved the sale online: the card has been charged. */
    data class ApprovedOnline(val sale: Sale) : SaleOutcome

    /**
     * Store and Forward declined the sale for [reason]. [message] is the SDK's readable message
     * (translated, with the limit as money when it has one), or blank when it gave none.
     */
    data class Declined(val reason: DeclineReason, val message: String = "") : SaleOutcome

    /**
     * Handpoint declined the sale online. [message] is the SDK's readable message (translated
     * into the card or device language), or blank when it gave none or only a raw key; then
     * [reason] is what the key says.
     */
    data class DeclinedOnline(val message: String, val reason: DeclineReason = DeclineReason.OTHER) : SaleOutcome

    data object Cancelled : SaleOutcome

    /**
     * The sale did not complete, or its outcome is unknown (see the `OUTCOME_UNKNOWN` constants).
     * [reference] is the SDK's reference of a sale that started, when known.
     */
    data class Failed(val message: String, val reference: SaleReference? = null) : SaleOutcome
}

/** The sale an approval holds, or `null` when the outcome is not an approval. */
val SaleOutcome.approvedSale: Sale?
    get() = when (this) {
        is SaleOutcome.ApprovedOffline -> sale
        is SaleOutcome.ApprovedOnline -> sale
        else -> null
    }
