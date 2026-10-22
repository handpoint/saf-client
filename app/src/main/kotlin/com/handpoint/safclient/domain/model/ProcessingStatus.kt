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
}
