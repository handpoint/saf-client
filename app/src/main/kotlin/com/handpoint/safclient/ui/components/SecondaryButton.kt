package com.handpoint.safclient.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews

/**
 * The quieter action next to a [PrimaryButton] (Print receipt, Cancel, Done). Same size and
 * shape, ink outline on the canvas.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@SafPreviews
@Composable
private fun SecondaryButtonPreview() = SafPreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(text = "Print receipt", onClick = {})
        SecondaryButton(text = "Printing…", onClick = {}, enabled = false)
    }
}
