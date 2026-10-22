package com.handpoint.safclient.domain.model

sealed interface SaleOutcome {
    /** Store and Forward approved the sale: it is stored on the terminal and charged once sent. */
    data class ApprovedOffline(val sale: Sale) : SaleOutcome

    /** Handpoint approved the sale online: the card has been charged. */
    data class ApprovedOnline(val sale: Sale) : SaleOutcome

    /** Store and Forward declined the sale for a known [reason]. */
    data class Declined(val reason: DeclineReason) : SaleOutcome

    /** Handpoint declined the sale online. [message] is the SDK's, possibly translated. */
    data class DeclinedOnline(val message: String) : SaleOutcome

    data object Cancelled : SaleOutcome
    data class Failed(val message: String) : SaleOutcome
}

/** The sale an approval holds, or `null` when the outcome is not an approval. */
val SaleOutcome.approvedSale: Sale?
    get() = when (this) {
        is SaleOutcome.ApprovedOffline -> sale
        is SaleOutcome.ApprovedOnline -> sale
        else -> null
    }
