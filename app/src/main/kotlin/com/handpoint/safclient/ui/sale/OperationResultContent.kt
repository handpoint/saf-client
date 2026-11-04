package com.handpoint.safclient.ui.sale

import com.handpoint.safclient.ui.components.TransactionResultDialog
import com.handpoint.safclient.ui.components.SecondaryButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
    var viewing by rememberSaveable { mutableStateOf(false) }
    if (viewing) TransactionResultDialog(report.lines, report.resultJson, onDismiss = { viewing = false })
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(kind.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(report.summary, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        if (report.lines.isNotEmpty() || report.resultJson != null) {
            PrimaryButton(text = "View transaction result", onClick = { viewing = true }, emphasis = ButtonEmphasis.Accent)
        }
        PrimaryButton(text = "Done", onClick = onDone, emphasis = ButtonEmphasis.Positive)
    }
}
