package com.handpoint.safclient.ui.sale

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.approvedSale
import com.handpoint.safclient.ui.components.AmountText
import com.handpoint.safclient.ui.components.ButtonEmphasis
import com.handpoint.safclient.ui.components.PrimaryButton
import com.handpoint.safclient.ui.components.SecondaryButton
import com.handpoint.safclient.ui.components.StatusPill
import com.handpoint.safclient.ui.components.animationsEnabled
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import com.handpoint.safclient.ui.theme.Tone
import java.time.Instant
import java.util.Currency

/** The outcome of a sale: an animated mark, the amount, what happened, and what to do next. */
@Composable
fun ResultContent(
    outcome: SaleOutcome,
    amount: Money,
    printing: Boolean,
    onPrint: () -> Unit,
    onTryAgain: () -> Unit,
    onDone: () -> Unit,
    onChangeAmount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = resultCopy(outcome, amount.currency)
    val sale = outcome.approvedSale
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ResultMark(copy.kind)
            Spacer(Modifier.height(28.dp))
            AmountText(sale?.amount ?: amount, SafTheme.amounts.large)
            Spacer(Modifier.height(12.dp))
            Text(
                text = copy.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = copy.body,
                style = MaterialTheme.typography.bodyLarge,
                color = SafTheme.colors.textMuted,
                textAlign = TextAlign.Center,
            )
            if (sale != null) {
                Spacer(Modifier.height(20.dp))
                SaleSummary(sale)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                copy.canPrint -> {
                    SecondaryButton(
                        text = if (printing) "Printing…" else "Print receipt",
                        onClick = onPrint,
                        enabled = !printing,
                    )
                    PrimaryButton(text = "Done", onClick = onDone, emphasis = ButtonEmphasis.Accent)
                }
                copy.canChangeAmount -> {
                    PrimaryButton(text = "Change amount", onClick = onChangeAmount, emphasis = ButtonEmphasis.Accent)
                    SecondaryButton(text = "Done", onClick = onDone)
                }
                copy.canTryAgain -> {
                    PrimaryButton(text = "Try again", onClick = onTryAgain, emphasis = ButtonEmphasis.Accent)
                    SecondaryButton(text = "Done", onClick = onDone)
                }
                else -> PrimaryButton(text = "Done", onClick = onDone, emphasis = ButtonEmphasis.Accent)
            }
        }
    }
}

/** Masked card and entry mode, never the full card number; plus the sale's status. */
@Composable
private fun SaleSummary(sale: Sale) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = listOfNotNull(sale.maskedCard, sale.entryMode.label()).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = SafTheme.colors.textMuted,
        )
        Spacer(Modifier.width(10.dp))
        StatusPill(sale.status)
    }
}

private fun EntryMode.label(): String? = when (this) {
    EntryMode.CHIP -> "Chip"
    EntryMode.CONTACTLESS -> "Contactless"
    EntryMode.SWIPE -> "Swipe"
    EntryMode.MANUAL -> "Manual"
    EntryMode.UNKNOWN -> null
}

/** A tinted disc that scales in, then draws its check, cross or exclamation mark. */
@Composable
private fun ResultMark(kind: ResultKind) {
    val colors = SafTheme.colors
    val tone: Tone = when (kind) {
        ResultKind.APPROVED -> colors.paid
        ResultKind.DECLINED -> colors.failed
        ResultKind.UNKNOWN -> colors.stored
    }
    val animate = animationsEnabled()
    val scale = remember { Animatable(if (animate) 0.4f else 1f) }
    val draw = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(kind) {
        if (!animate) return@LaunchedEffect
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(kind) {
        if (!animate) return@LaunchedEffect
        draw.animateTo(1f, tween(durationMillis = 360, delayMillis = 140))
    }
    val description = when (kind) {
        ResultKind.APPROVED -> "Approved"
        ResultKind.DECLINED -> "Not completed"
        ResultKind.UNKNOWN -> "Not confirmed"
    }
    Canvas(
        Modifier
            .size(96.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = scale.value.coerceIn(0f, 1f)
            }
            .semantics { this.contentDescription = description },
    ) {
        drawCircle(tone.container)
        val s = size.minDimension
        val paths = when (kind) {
            ResultKind.APPROVED -> listOf(
                Path().apply {
                    moveTo(s * 0.30f, s * 0.52f)
                    lineTo(s * 0.44f, s * 0.66f)
                    lineTo(s * 0.71f, s * 0.38f)
                },
            )
            ResultKind.DECLINED -> listOf(
                Path().apply { moveTo(s * 0.35f, s * 0.35f); lineTo(s * 0.65f, s * 0.65f) },
                Path().apply { moveTo(s * 0.65f, s * 0.35f); lineTo(s * 0.35f, s * 0.65f) },
            )
            ResultKind.UNKNOWN -> listOf(
                Path().apply { moveTo(s * 0.5f, s * 0.28f); lineTo(s * 0.5f, s * 0.56f) },
            )
        }
        val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val measure = PathMeasure()
        paths.forEach { path ->
            measure.setPath(path, forceClosed = false)
            val partial = Path()
            measure.getSegment(0f, measure.length * draw.value, partial, startWithMoveTo = true)
            drawPath(partial, tone.content, style = stroke)
        }
        // The exclamation dot appears once the stem is drawn.
        if (kind == ResultKind.UNKNOWN && draw.value >= 1f) {
            drawCircle(tone.content, radius = stroke.width / 2, center = Offset(s * 0.5f, s * 0.70f))
        }
    }
}

@SafPreviews
@Composable
private fun ApprovedPreview() = SafPreviewSurface {
    val usd = Currency.getInstance("USD")
    val sale = Sale(
        reference = SaleReference("preview"),
        amount = Money(1250, usd),
        maskedCard = "•••• 4242",
        entryMode = EntryMode.CONTACTLESS,
        approvedAt = Instant.parse("2026-10-22T10:14:00Z"),
        sentAt = null,
        status = ProcessingStatus.STORED,
        statusCheckedAt = null,
        merchantReceipt = null,
        customerReceipt = null,
    )
    ResultContent(SaleOutcome.ApprovedOffline(sale), Money(1250, usd), false, {}, {}, {}, {})
}
