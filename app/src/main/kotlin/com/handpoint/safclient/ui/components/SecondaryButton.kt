package com.handpoint.safclient.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews

/**
 * The quieter action next to [PrimaryButton] (Print receipt, Cancel, Done). Same size and
 * shape, ink outline on the canvas. While [loading] it shows a spinner, keeps its colours and
 * ignores taps.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val active = enabled || loading
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .semantics { if (loading) stateDescription = "Working" },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (active) colors.outline else colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.onSurface,
            disabledContentColor = if (loading) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = colors.onSurface,
                strokeWidth = 2.5.dp,
            )
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@SafPreviews
@Composable
private fun SecondaryButtonPreview() = SafPreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(text = "Print receipt", onClick = {})
        SecondaryButton(text = "Refresh status", onClick = {}, loading = true)
        SecondaryButton(text = "Printing…", onClick = {}, enabled = false)
    }
}
