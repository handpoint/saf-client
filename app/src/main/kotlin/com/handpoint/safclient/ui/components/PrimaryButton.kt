package com.handpoint.safclient.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme

/** Minimum height of a primary action; comfortably above the 56 dp touch target. */
private val PrimaryButtonHeight = 60.dp

/** Colour of a [PrimaryButton]: [Ink] by default, [Accent] for the screen's lead action next to dark content. */
enum class ButtonEmphasis { Ink, Accent }

/**
 * The single main action of a screen (Charge, Send now). Full width, 60 dp tall.
 * While [loading] it shows a spinner, keeps its colour and ignores taps, so a double tap
 * cannot start the action twice.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    emphasis: ButtonEmphasis = ButtonEmphasis.Ink,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(stiffness = 1_400f),
        label = "buttonScale",
    )
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (emphasis) {
        ButtonEmphasis.Ink -> colors.primary to colors.onPrimary
        ButtonEmphasis.Accent -> SafTheme.colors.accentAction.let { it.container to it.content }
    }
    // While loading the button is not clickable, but it keeps its active colours.
    val buttonColors = if (loading) {
        ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container,
            disabledContentColor = content,
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = colors.onSurface.copy(alpha = 0.12f),
            disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
        )
    }
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PrimaryButtonHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics { if (loading) stateDescription = "Working" },
        shape = MaterialTheme.shapes.medium,
        colors = buttonColors,
        interactionSource = interaction,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "buttonContent",
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = content,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@SafPreviews
@Composable
private fun PrimaryButtonPreview() = SafPreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton(text = "Charge $12.50", onClick = {})
        PrimaryButton(text = "Send now", onClick = {}, loading = true)
        PrimaryButton(text = "Charge", onClick = {}, enabled = false)
    }
}

@SafPreviews
@Composable
private fun PrimaryButtonAccentPreview() = SafPreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton(text = "New sale", onClick = {}, emphasis = ButtonEmphasis.Accent)
        PrimaryButton(text = "New sale", onClick = {}, emphasis = ButtonEmphasis.Accent, loading = true)
        PrimaryButton(text = "New sale", onClick = {}, emphasis = ButtonEmphasis.Accent, enabled = false)
    }
}
