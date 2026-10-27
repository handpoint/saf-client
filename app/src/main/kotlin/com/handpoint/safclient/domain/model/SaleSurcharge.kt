package com.handpoint.safclient.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The surcharge of one sale: [percent] of the base amount, sent to the gateway as [sent].
 *
 * The app only asks for it. The gateway decides whether to apply it, from the card's funding
 * type (it removes it for debit and prepaid cards). [applied] and [reason] are what the SDK's
 * result says about that; both are `null` when the result carries no surcharge, which is what
 * the app needs to see when a sale is stored offline and the card type is not known yet.
 */
data class SaleSurcharge(
    val percent: BigDecimal,
    val sent: Money,
    val applied: Boolean? = null,
    val reason: String? = null,
) {
    /** What the card is charged for [base]: the surcharge counts unless the gateway removed it. */
    fun totalFor(base: Money): Money = if (applied == false) base else base + sent

    /** The same surcharge with the answer the SDK gave back. */
    fun withResult(applied: Boolean?, reason: String?): SaleSurcharge =
        copy(applied = applied, reason = reason?.ifBlank { null })

    companion object {
        /** The surcharge asked for a sale of [base]: [percent] of it, rounded half up to the minor unit. */
        fun of(base: Money, percent: BigDecimal): SaleSurcharge {
            val minor = BigDecimal.valueOf(base.minorUnits)
                .multiply(percent)
                .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                .longValueExact()
            return SaleSurcharge(percent = percent, sent = Money(minor, base.currency))
        }

        private val HUNDRED = BigDecimal(100)
    }
}

/** What the card is charged: the amount plus its surcharge, if any. */
val Sale.total: Money get() = surcharge?.totalFor(amount) ?: amount

/** "3%", "2.5%": no trailing zeros. */
fun BigDecimal.formatPercent(): String = stripTrailingZeros().toPlainString() + "%"
