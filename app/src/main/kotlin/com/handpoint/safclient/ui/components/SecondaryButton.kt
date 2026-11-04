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
import com.handpoint.safclient.ui.theme.SafTheme

/** What a [SecondaryButton] means: its outline and label take the colour of the tone. */
enum class ButtonTone { Neutral, Positive, Negative, Warning }

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
    tone: ButtonTone = ButtonTone.Neutral,
) {
    val colors = MaterialTheme.colorScheme
    val saf = SafTheme.colors
    val toneColor = when (tone) {
        ButtonTone.Neutral -> colors.onSurface
        ButtonTone.Positive -> saf.positiveAction.container
        ButtonTone.Negative -> saf.negativeAction.container
        ButtonTone.Warning -> saf.stored.content
    }
    val active = enabled || loading
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .semantics { if (loading) stateDescription = "Working" },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (tone == ButtonTone.Neutral) 1.dp else 1.5.dp, if (!active) colors.outlineVariant else if (tone == ButtonTone.Neutral) colors.outline else toneColor),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = toneColor,
            disabledContentColor = if (loading) toneColor else colors.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = toneColor,
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
