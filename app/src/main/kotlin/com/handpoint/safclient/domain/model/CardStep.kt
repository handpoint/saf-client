package com.handpoint.safclient.domain.model

data class CardStep(val kind: Kind, val cancelAllowed: Boolean) {
    enum class Kind { PRESENT_CARD, CARD_READ, ENTER_PIN, PROCESSING, REMOVE_CARD }
}
