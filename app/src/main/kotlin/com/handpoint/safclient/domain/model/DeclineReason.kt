package com.handpoint.safclient.domain.model

enum class DeclineReason {
    PREPAID_CARD,
    CARD_LIMIT_REACHED,
    TERMINAL_LIMIT_REACHED,
    CARD_EXPIRED,
    CURRENCY_NOT_ACCEPTED,
    CARD_NOT_ACCEPTED,
    OTHER,
}
