package com.handpoint.safclient.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleSurcharge
import com.handpoint.safclient.domain.model.formatPercent
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import java.math.BigDecimal
import java.util.Currency

/**
 * Subtotal, surcharge (with its percent) and total of a sale.
 *
 * With [showResult] the surcharge also says what the SDK answered: applied, removed (with the
 * gateway's reason) or not reported. Without it, the breakdown is the one the customer accepts
 * before paying, so it only says what will be sent.
 */
@Composable
fun SurchargeBreakdown(
    base: Money,
    surcharge: SaleSurcharge,
    modifier: Modifier = Modifier,
    showResult: Boolean = false,
) {
    val removed = showResult && surcharge.applied == false
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BreakdownRow(label = "Subtotal", value = base.format())
        BreakdownRow(
            label = "Surcharge (${surcharge.percent.formatPercent()})",
            value = if (removed) "Removed" else surcharge.sent.format(),
            emphasis = true,
        )
        if (showResult) {
            Text(
                text = surcharge.resultText(),
                style = MaterialTheme.typography.bodySmall,
                color = SafTheme.colors.textMuted,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = SafTheme.colors.hairline)
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("TOTAL", style = MaterialTheme.typography.labelLarge, color = SafTheme.colors.textMuted)
            AmountText(surcharge.totalFor(base), SafTheme.amounts.large)
        }
    }
}

/** What the SDK said about the surcharge, in the words of a merchant. */
fun SaleSurcharge.resultText(): String = when (applied) {
    true -> "Applied by the gateway"
    false -> "Not applied" + (reason?.let { ": $it" } ?: " (the card type does not allow it)")
    null -> "Sent with the sale. The result did not say if it was applied."
}

@Composable
private fun BreakdownRow(label: String, value: String, emphasis: Boolean = false) {
    val color = if (emphasis) SafTheme.colors.stored.content else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@SafPreviews
@Composable
private fun SurchargeBreakdownPreview() = SafPreviewSurface {
    val usd = Currency.getInstance("USD")
    val base = Money(5000, usd)
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SurchargeBreakdown(base, SaleSurcharge.of(base, BigDecimal("3")))
        SurchargeBreakdown(base, SaleSurcharge.of(base, BigDecimal("3")).withResult(false, "Debit card"), showResult = true)
    }
}
