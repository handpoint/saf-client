package com.handpoint.safclient.ui.operations

import com.handpoint.safclient.domain.model.MotoChannel
import com.handpoint.safclient.domain.model.OperationKind
import com.handpoint.safclient.domain.model.SaleTax
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the tester chose on the New operation screen. Every amount is text in minor units (800 is
 * 8.00 in a two-decimal currency); blank means "not sent". The sale amount comes later, from the
 * keypad.
 */
data class OperationForm(
    val kind: OperationKind = OperationKind.SALE,
    val originalTransactionId: String = "",
    val surcharge: String = "",
    val legacyTax: String = "",
    val level2Tax: String = "",
    val taxExempt: Boolean = false,
    val purchaseOrder: String = "",
    val motoChannel: MotoChannel = MotoChannel.TELEPHONE_ORDER,
    val avsFields: Boolean = false,
    /** Add the surcharge of Settings to a sale, when Settings has it on. */
    val useAppSurcharge: Boolean = true,
    /** Send the surcharge and tax fields above. Off by default: the operation carries none. */
    val useExtras: Boolean = false,
)

/** The surcharge and tax fields of an [OperationForm], as numbers. */
data class OperationExtras(val surchargeMinor: Long?, val tax: SaleTax) {
    companion object {
        val NONE = OperationExtras(null, SaleTax.NONE)
    }
}

/** The extras of this form, or `null` when one of its amounts is not a number. */
fun OperationForm.toExtras(): OperationExtras? {
    if (!useExtras) return OperationExtras.NONE
    val surchargeMinor = parseMinor(surcharge) ?: return null
    val legacy = parseMinor(legacyTax) ?: return null
    val level2 = parseMinor(level2Tax) ?: return null
    return OperationExtras(
        surchargeMinor = surchargeMinor.value,
        tax = SaleTax(
            legacyTaxMinor = legacy.value,
            level2TaxMinor = level2.value,
            taxExempt = taxExempt,
            // As typed: the SDK checks it (required with taxInformation, letters and digits, up to 25).
            purchaseOrder = purchaseOrder.trim().ifEmpty { null },
        ),
    )
}

private class Parsed(val value: Long?)

/** Blank is "not set"; digits only otherwise. `null` when the text is not a number. */
private fun parseMinor(text: String): Parsed? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return Parsed(null)
    if (trimmed.length > 9 || !trimmed.all(Char::isDigit)) return null
    return Parsed(trimmed.toLong())
}

/**
 * The hand-over from the New operation screen to the keypad flow: the operation the next charge
 * runs, and the transaction ID of the last operation that ended (for a linked refund).
 */
@Singleton
class OperationDraft @Inject constructor() {
    @Volatile
    var form: OperationForm = OperationForm()

    @Volatile
    var lastTransactionId: String? = null
}
