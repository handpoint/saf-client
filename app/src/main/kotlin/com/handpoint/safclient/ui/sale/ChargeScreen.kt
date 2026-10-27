package com.handpoint.safclient.ui.sale

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleSurcharge
import com.handpoint.safclient.domain.model.formatPercent
import com.handpoint.safclient.ui.components.AmountText
import com.handpoint.safclient.ui.components.SecondaryButton
import com.handpoint.safclient.ui.components.animationsEnabled
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import java.util.Currency

/**
 * The sale is running on the terminal: a pulsing card mark, the amount, what the terminal waits
 * for, and Cancel while the terminal still allows it.
 */
@Composable
fun CardStepContent(
    amount: Money,
    step: CardStep?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    surcharge: SaleSurcharge? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CardPulse(Modifier.size(200.dp))
            Spacer(Modifier.height(32.dp))
            AmountText(surcharge?.totalFor(amount) ?: amount, SafTheme.amounts.large)
            if (surcharge != null) {
                Text(
                    text = "Includes ${surcharge.percent.formatPercent()} surcharge: ${surcharge.sent.format()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafTheme.colors.textMuted,
                )
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = step.message(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "cardStep",
            ) { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
        CancelSlot(visible = step?.cancelAllowed == true, onCancel = onCancel)
    }
}

/** Keeps the button's room so the content does not jump when Cancel comes and goes. */
@Composable
private fun CancelSlot(visible: Boolean, onCancel: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            SecondaryButton(text = "Cancel", onClick = onCancel)
        }
    }
}

/** A contactless card with rings that pulse outwards. Still when system animations are off. */
@Composable
private fun CardPulse(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val accent = SafTheme.colors.accent
    val card = MaterialTheme.colorScheme.inverseSurface
    val onCard = MaterialTheme.colorScheme.inverseOnSurface
    val progress = if (animationsEnabled()) {
        val transition = rememberInfiniteTransition(label = "cardPulse")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart),
            label = "pulse",
        )
        value
    } else {
        0.35f
    }
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2
        // Two rings, half a cycle apart, grow and fade.
        listOf(progress, (progress + 0.5f) % 1f).forEach { p ->
            drawCircle(
                color = accent.copy(alpha = 0.22f * (1f - p)),
                radius = maxRadius * (0.55f + 0.45f * p),
                center = center,
            )
        }
        drawCircle(color = accent.copy(alpha = 0.12f), radius = maxRadius * 0.55f, center = center)
        drawCard(center, maxRadius * 0.62f, card, onCard)
        drawWaves(Offset(center.x + maxRadius * 0.62f, center.y - maxRadius * 0.48f), maxRadius * 0.16f, ink)
    }
}

private fun DrawScope.drawCard(center: Offset, width: Float, card: Color, onCard: Color) {
    val height = width * 0.63f
    val topLeft = Offset(center.x - width / 2, center.y - height / 2)
    drawRoundRect(card, topLeft, Size(width, height), CornerRadius(width * 0.08f))
    // Chip.
    val chip = Size(width * 0.18f, height * 0.24f)
    drawRoundRect(
        color = onCard.copy(alpha = 0.7f),
        topLeft = Offset(topLeft.x + width * 0.12f, topLeft.y + height * 0.32f),
        size = chip,
        cornerRadius = CornerRadius(chip.width * 0.2f),
    )
    // Number line.
    drawLine(
        color = onCard.copy(alpha = 0.45f),
        start = Offset(topLeft.x + width * 0.12f, topLeft.y + height * 0.76f),
        end = Offset(topLeft.x + width * 0.62f, topLeft.y + height * 0.76f),
        strokeWidth = height * 0.06f,
        cap = StrokeCap.Round,
    )
}

/** The contactless symbol: three arcs opening to the right. */
private fun DrawScope.drawWaves(origin: Offset, step: Float, color: Color) {
    val stroke = Stroke(width = step * 0.32f, cap = StrokeCap.Round)
    for (i in 1..3) {
        val radius = step * i
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(origin.x - radius, origin.y - radius),
            size = Size(radius * 2, radius * 2),
            style = stroke,
        )
    }
}

@SafPreviews
@Composable
private fun CardStepContentPreview() = SafPreviewSurface {
    CardStepContent(
        amount = Money(1250, Currency.getInstance("USD")),
        step = CardStep(CardStep.Kind.PRESENT_CARD, cancelAllowed = true),
        onCancel = {},
    )
}
