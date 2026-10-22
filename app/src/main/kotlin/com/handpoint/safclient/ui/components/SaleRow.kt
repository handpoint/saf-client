package com.handpoint.safclient.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import com.handpoint.safclient.ui.theme.tabular
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * One sale in a list: amount, masked card ("Card" when unknown) and local time (short, locale format) on the left,
 * status on the right. Read out as a single sentence by screen readers.
 */
@Composable
fun SaleRow(
    sale: Sale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val amount = sale.amount.format()
    val card = sale.maskedCard ?: "Card"
    val time = TimeFormat.format(sale.approvedAt.atZone(zone))
    val status = sale.status.label()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$amount, $card, $time, $status"
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AmountText(sale.amount, SafTheme.amounts.row)
            Text(
                text = "$card · $time",
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = SafTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        StatusPill(sale.status)
    }
}

private fun previewSale(cents: Long, card: String?, status: ProcessingStatus, at: String) = Sale(
    reference = SaleReference("ref-$cents"),
    amount = Money(cents, Currency.getInstance("USD")),
    maskedCard = card,
    entryMode = EntryMode.CONTACTLESS,
    approvedAt = Instant.parse(at),
    sentAt = null,
    status = status,
    statusCheckedAt = null,
    merchantReceipt = null,
    customerReceipt = null,
)

@SafPreviews
@Composable
private fun SaleRowPreview() = SafPreviewSurface {
    val utc = ZoneId.of("UTC")
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        SaleRow(previewSale(1250, "•••• 4242", ProcessingStatus.STORED, "2026-10-22T10:14:00Z"), {}, zone = utc)
        HorizontalDivider(color = SafTheme.colors.hairline)
        SaleRow(previewSale(820, null, ProcessingStatus.SUCCEEDED, "2026-10-22T09:52:00Z"), {}, zone = utc)
        HorizontalDivider(color = SafTheme.colors.hairline)
        SaleRow(previewSale(6380, "•••• 0005", ProcessingStatus.FAILED, "2026-10-22T09:03:00Z"), {}, zone = utc)
    }
}
