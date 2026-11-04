package com.handpoint.safclient.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.ResultLine
import com.handpoint.safclient.ui.theme.SafTheme

/**
 * What the SDK's `TransactionResult` carried, field by field, as the integrator reads it. For
 * testers: it shows the value and the unit of `surcharge`, `taxAmount` and the amounts, and
 * whether `surcharge` is `null`. The text can be selected and copied.
 */
@Composable
fun ResultValuesCard(lines: List<ResultLine>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "TransactionResult",
                    style = MaterialTheme.typography.labelLarge,
                    color = SafTheme.colors.textMuted,
                )
                lines.forEach { line ->
                    Text(
                        text = "${line.label}: ${line.value}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
