package com.handpoint.safclient.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.ResultLine
import com.handpoint.safclient.ui.theme.SafTheme
import org.json.JSONObject

/**
 * The `TransactionResult` of an operation, for testers: the key values with `null` written out
 * (surcharge, taxAmount), and the full JSON as the SDK serializes it. The SDK leaves a null field
 * out of its JSON, so a missing key there means `null`. The text can be selected and copied.
 */
@Composable
fun TransactionResultDialog(lines: List<ResultLine>, json: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction result") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (lines.isNotEmpty()) ResultValuesCard(lines)
                if (json != null) {
                    val pretty = remember(json) { runCatching { JSONObject(json).toString(2) }.getOrDefault(json) }
                    Text(
                        "Full JSON, as the SDK serializes it. A field that is null is not in it.",
                        style = MaterialTheme.typography.labelLarge,
                        color = SafTheme.colors.textMuted,
                    )
                    SelectionContainer {
                        Text(
                            pretty,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                if (lines.isEmpty() && json == null) Text("This operation has no transaction result.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
