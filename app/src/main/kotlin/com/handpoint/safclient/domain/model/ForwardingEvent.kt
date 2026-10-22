package com.handpoint.safclient.domain.model

enum class ForwardingError { NETWORK, CORRUPTED, FAILED }

sealed interface ForwardingEvent {
    data object NothingToSend : ForwardingEvent
    data class Started(val total: Int) : ForwardingEvent
    data class Sent(val reference: SaleReference, val sent: Int, val total: Int) : ForwardingEvent
    data class Finished(val result: ForwardingResult) : ForwardingEvent
}

sealed interface ForwardingResult {
    data class AllSent(val sent: Int) : ForwardingResult
    data class Partial(
        val sent: Int,
        val corrupted: Int,
        val failed: Int,
        val total: Int,
        val error: ForwardingError,
    ) : ForwardingResult

    data class NoneSent(
        val error: ForwardingError,
        val corrupted: Int,
        val failed: Int,
        val total: Int,
    ) : ForwardingResult
}
