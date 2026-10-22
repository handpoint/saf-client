package com.handpoint.safclient.domain.model

enum class ProcessingStatus(val isFinal: Boolean) {
    STORED(isFinal = false),
    IN_PROGRESS(isFinal = false),
    SUCCEEDED(isFinal = true),
    FAILED(isFinal = true),
    CAPTURED(isFinal = true),
    REFUNDED(isFinal = true),
    CANCELLED(isFinal = true),
    NOT_FOUND(isFinal = false),

    /**
     * App-only status, never an SDK answer: an online sale whose outcome the app could not
     * confirm. The card may have been charged. Nothing asks for its status automatically: the
     * merchant checks with Handpoint.
     */
    UNKNOWN(isFinal = false),
}
