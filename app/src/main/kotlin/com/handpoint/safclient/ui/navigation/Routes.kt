package com.handpoint.safclient.ui.navigation

import android.net.Uri
import com.handpoint.safclient.domain.model.SaleReference

/** Navigation routes. The charge flow (amount, card, result) lives inside [AMOUNT]. */
object Routes {
    const val HOME = "home"
    const val AMOUNT = "amount"
    const val SENDING = "sending"
    const val SETTINGS = "settings"

    const val SALE_REFERENCE = "reference"
    const val SALE = "sale/{$SALE_REFERENCE}"

    /** Route to the detail of one sale. */
    fun sale(reference: SaleReference): String = "sale/${Uri.encode(reference.value)}"
}
