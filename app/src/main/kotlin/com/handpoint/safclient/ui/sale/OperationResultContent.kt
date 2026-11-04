package com.handpoint.safclient.ui.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.OperationKind
import com.handpoint.safclient.domain.model.OperationReport
import com.handpoint.safclient.ui.components.ButtonEmphasis
import com.handpoint.safclient.ui.components.PrimaryButton
import com.handpoint.safclient.ui.components.ResultValuesCard

/**
 * The end of a test operation (a refund, a MoTo operation...): what the SDK answered, and the
 * values of its `TransactionResult` when it sent one.
 */
@Composable
fun OperationResultContent(kind: OperationKind, report: OperationReport, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(kind.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(report.summary, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        if (report.lines.isNotEmpty()) ResultValuesCard(report.lines)
        PrimaryButton(text = "Done", onClick = onDone, emphasis = ButtonEmphasis.Positive)
    }
}
