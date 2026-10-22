package com.handpoint.safclient.domain.model

sealed interface SaleOutcome {
    data class ApprovedOffline(val sale: Sale) : SaleOutcome
    data class Declined(val reason: DeclineReason) : SaleOutcome
    data object Cancelled : SaleOutcome
    data class Failed(val message: String) : SaleOutcome
}
