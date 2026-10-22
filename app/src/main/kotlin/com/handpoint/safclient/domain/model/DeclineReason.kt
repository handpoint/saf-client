package com.handpoint.safclient.domain.model

/** Why a sale was declined, as far as the SDK's code says. */
enum class DeclineReason {
    PREPAID_CARD,
    CARD_LIMIT_REACHED,
    TERMINAL_LIMIT_REACHED,

    /** The amount is over the terminal's offline limit per sale. Another card does not help. */
    OVER_SALE_LIMIT,

    /** The amount is not valid (for example zero). */
    INVALID_AMOUNT,
    CARD_EXPIRED,
    CURRENCY_NOT_ACCEPTED,
    CARD_NOT_ACCEPTED,

    /** The card was declined, with no more detail. */
    DECLINED,
    OTHER,
}
