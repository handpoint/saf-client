package com.handpoint.safclient.ui.navigation

import android.net.Uri
import com.handpoint.safclient.domain.model.SaleReference

/** Navigation routes. The charge flow (amount, card, result) lives inside [AMOUNT]. */
object Routes {
    const val HOME = "home"
    const val AMOUNT = "amount?amount={amount}"
    const val AMOUNT_VALUE = "amount"
    const val SENDING = "sending"
    const val SETTINGS = "settings"
    const val OPERATIONS = "operations?txn={txn}&amount={amount}&moto={moto}"
    const val OPERATIONS_TXN = "txn"
    const val OPERATIONS_AMOUNT = "amount"
    const val OPERATIONS_MOTO = "moto"

    /** New operation: choose the operation and its extra fields. */
    fun operations(): String = "operations"

    /**
     * The same screen for a refund of a sale in the history: the refund kinds, with [transactionId]
     * and [amountMinor] filled in; [moto] starts on the MoTo refund.
     */
    fun refundSetup(transactionId: String, amountMinor: Long, moto: Boolean): String =
        "operations?txn=${Uri.encode(transactionId)}&amount=$amountMinor&moto=$moto"

    /** The keypad flow of the operation chosen on [OPERATIONS], with [prefillMinor] already entered. */
    fun amount(prefillMinor: Long? = null): String = if (prefillMinor == null) "amount" else "amount?amount=$prefillMinor"

    const val SALE_REFERENCE = "reference"
    const val SALE = "sale/{$SALE_REFERENCE}"

    /** Route to the detail of one sale. */
    fun sale(reference: SaleReference): String = "sale/${Uri.encode(reference.value)}"
}
