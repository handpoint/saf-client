package com.handpoint.safclient.domain.model

/**
 * The tax fields a test sale sends next to the amount, all in minor units (800 is 8.00).
 *
 * - [legacyTaxMinor]: the deprecated `Options.taxAmount`.
 * - [level2TaxMinor] and [taxExempt]: `Options.taxInformation` (Level II). When both tax fields are
 *   set, SDK 7.1014.5 adds only the Level II tax to the amount.
 * - [purchaseOrder]: `Options.purchaseOrderNumber` (Level II).
 *
 * This is test input, not a merchant feature: it lets a tester reproduce SDKS-465 and SDKS-468.
 */
data class SaleTax(
    val legacyTaxMinor: Long? = null,
    val level2TaxMinor: Long? = null,
    val taxExempt: Boolean = false,
    val purchaseOrder: String? = null,
) {
    /** `true` when nothing is sent: the sale carries no tax field. */
    val isEmpty: Boolean get() = this == NONE

    /** `true` when the sale sends `taxInformation`. */
    val hasLevel2: Boolean get() = level2TaxMinor != null || taxExempt

    companion object {
        val NONE = SaleTax()
    }
}

/** One value of the SDK's `TransactionResult`, as the integrator reads it. */
data class ResultLine(val label: String, val value: String)
