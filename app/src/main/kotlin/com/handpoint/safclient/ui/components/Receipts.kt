package com.handpoint.safclient.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.ui.theme.SafTheme

/**
 * The receipts the SDK returned for [sale], as they are: the customer copy and the merchant
 * copy. When the terminal sends none (a decline, an unconfirmed sale) it says so.
 */
@Composable
fun ReceiptsContent(sale: Sale, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReceiptCard("Customer receipt", sale.customerReceipt)
        ReceiptCard("Merchant receipt", sale.merchantReceipt)
    }
}

/** [ReceiptsContent] in a dialog, for screens that have no room for it. */
@Composable
fun ReceiptsDialog(sale: Sale, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receipts") },
        text = { ReceiptsContent(sale, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ReceiptCard(title: String, receipt: String?) {
    val text = remember(receipt) { receipt?.let(::readableReceipt) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = SafTheme.colors.textMuted,
            modifier = Modifier.semantics { heading() },
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                text = text ?: "The terminal sent no receipt for this sale.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (text == null) SafTheme.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** The SDK's receipts can be HTML: show their text, never markup. A blank receipt is no receipt. */
internal fun readableReceipt(raw: String): String? {
    val text = if ('<' in raw) {
        HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
    } else {
        raw
    }
    return text.trim().ifEmpty { null }
}
