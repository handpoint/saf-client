package com.handpoint.safclient.ui.sending

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handpoint.safclient.R
import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.ui.components.AmountText
import com.handpoint.safclient.ui.components.ButtonEmphasis
import com.handpoint.safclient.ui.components.PrimaryButton
import com.handpoint.safclient.ui.components.SecondaryButton
import com.handpoint.safclient.ui.theme.PillShape
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import com.handpoint.safclient.ui.theme.Tone
import java.util.Currency

/** The Send now screen, wired to [SendingViewModel]. Back is allowed: the run goes on without the screen. */
@Composable
fun SendingRoute(viewModel: SendingViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    SendingScreen(state = state, rows = rows, onTryAgain = viewModel::retry, onDone = onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendingScreen(
    state: SendingUiState,
    rows: List<SendingRow>,
    onTryAgain: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    Scaffold(
        modifier = modifier,
        containerColor = background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
            )
        },
        bottomBar = {
            if (state is SendingUiState.Done) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.result.canTryAgain()) {
                        PrimaryButton(text = "Try again", onClick = onTryAgain, emphasis = ButtonEmphasis.Accent)
                        SecondaryButton(text = "Done", onClick = onDone)
                    } else {
                        PrimaryButton(text = "Done", onClick = onDone, emphasis = ButtonEmphasis.Accent)
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item(key = "status") {
                StatusCard(state)
            }
            if (rows.isNotEmpty()) {
                item(key = "gap") { Spacer(Modifier.height(16.dp)) }
                itemsIndexed(rows, key = { _, row -> row.reference.value }) { index, row ->
                    SaleLine(row, first = index == 0, last = index == rows.lastIndex)
                }
            }
        }
    }
}

/** The dark hero card: progress while the run is active, the outcome once it ends. */
@Composable
private fun StatusCard(state: SendingUiState) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { it is SendingUiState.Done },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sendingState",
        ) { current ->
            Column(Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                when (current) {
                    is SendingUiState.Done -> DoneSummary(current.result)
                    else -> Progress(current)
                }
            }
        }
    }
}

@Composable
private fun Progress(state: SendingUiState) {
    val muted = LocalContentColor.current.copy(alpha = 0.8f)
    Text("Sending to Handpoint", style = MaterialTheme.typography.labelLarge, color = muted)
    Spacer(Modifier.height(6.dp))
    val running = state as? SendingUiState.Running
    Text(
        text = running?.let { "${it.sent} of ${it.total}" } ?: " ",
        style = SafTheme.amounts.large,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(16.dp))
    val trackColor = LocalContentColor.current.copy(alpha = 0.18f)
    val barColor = SafTheme.colors.accent
    if (running != null && running.total > 0) {
        val progress by animateFloatAsState(running.sent.toFloat() / running.total, label = "sendProgress")
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = barColor,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = barColor,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    }
    Spacer(Modifier.height(16.dp))
    Text("Keep the terminal on and connected.", style = MaterialTheme.typography.bodyMedium, color = muted)
}

private enum class DoneMark { CHECK, WARNING, CROSS, NONE }

@Composable
private fun DoneSummary(result: ForwardingResult?) {
    val (mark, title, body) = when (result) {
        null -> Triple(DoneMark.NONE, "Nothing to send", null)
        is ForwardingResult.AllSent -> Triple(DoneMark.CHECK, "All sales sent", null)
        is ForwardingResult.Partial -> Triple(
            DoneMark.WARNING,
            "${result.sent} of ${result.total} sent",
            "${result.total - result.sent} still on this terminal",
        )
        is ForwardingResult.NoneSent -> if (result.error == ForwardingError.NETWORK) {
            Triple(DoneMark.WARNING, "No connection. Your sales are safe on this terminal.", null)
        } else {
            Triple(DoneMark.CROSS, "Sales couldn't be sent.", null)
        }
    }
    DoneIcon(mark)
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    if (body != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun DoneIcon(mark: DoneMark) {
    val colors = SafTheme.colors
    val tone = when (mark) {
        DoneMark.CHECK -> colors.paid
        DoneMark.WARNING -> colors.stored
        DoneMark.CROSS -> colors.failed
        DoneMark.NONE -> colors.neutral
    }
    Canvas(Modifier.size(44.dp)) {
        drawCircle(tone.container)
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tone.content, Offset(s * x1, s * y1), Offset(s * x2, s * y2), stroke.width, StrokeCap.Round)
        when (mark) {
            DoneMark.CHECK -> {
                line(0.30f, 0.52f, 0.44f, 0.66f)
                line(0.44f, 0.66f, 0.71f, 0.38f)
            }
            DoneMark.WARNING -> {
                line(0.5f, 0.28f, 0.5f, 0.56f)
                drawCircle(tone.content, stroke.width / 2, Offset(s * 0.5f, s * 0.70f))
            }
            DoneMark.CROSS -> {
                line(0.35f, 0.35f, 0.65f, 0.65f)
                line(0.65f, 0.35f, 0.35f, 0.65f)
            }
            DoneMark.NONE -> line(0.32f, 0.5f, 0.68f, 0.5f)
        }
    }
}

/** One sale of the run in a grouped list: amount on the left, its sending status on the right. */
@Composable
private fun SaleLine(row: SendingRow, first: Boolean, last: Boolean) {
    val corner = MaterialTheme.shapes.medium
    val shape = when {
        first && last -> corner
        first -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        last -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RoundedCornerShape(0.dp)
    }
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer, shape)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmountText(row.amount, SafTheme.amounts.row, Modifier.weight(1f))
            RowStatusTag(row.status)
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), color = SafTheme.colors.hairline)
    }
}

@Composable
private fun RowStatusTag(status: SendingRowStatus) {
    val colors = SafTheme.colors
    val (label, tone) = when (status) {
        SendingRowStatus.SENT -> "Sent" to colors.paid
        SendingRowStatus.WAITING -> "Waiting" to colors.neutral
        SendingRowStatus.STILL_ON_TERMINAL -> "Still on terminal" to colors.stored
    }
    val content by animateColorAsState(tone.content, label = "rowStatusContent")
    val container by animateColorAsState(tone.container, label = "rowStatusContainer")
    StatusTag(label, Tone(content, container))
}

@Composable
private fun StatusTag(label: String, tone: Tone) {
    Row(
        modifier = Modifier
            .background(tone.container, PillShape)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(6.dp).background(tone.content, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = tone.content, style = MaterialTheme.typography.labelMedium)
    }
}

@SafPreviews
@Composable
private fun SendingRunningPreview() = SafPreviewSurface {
    val usd = Currency.getInstance("USD")
    SendingScreen(
        state = SendingUiState.Running(sent = 2, total = 3),
        rows = listOf(
            SendingRow(SaleReference("a"), Money(1250, usd), SendingRowStatus.SENT),
            SendingRow(SaleReference("b"), Money(4700, usd), SendingRowStatus.SENT),
            SendingRow(SaleReference("c"), Money(2500, usd), SendingRowStatus.WAITING),
        ),
        onTryAgain = {},
        onDone = {},
    )
}

@SafPreviews
@Composable
private fun SendingPartialPreview() = SafPreviewSurface {
    val usd = Currency.getInstance("USD")
    SendingScreen(
        state = SendingUiState.Done(ForwardingResult.Partial(1, 0, 2, 3, ForwardingError.NETWORK)),
        rows = listOf(
            SendingRow(SaleReference("a"), Money(1250, usd), SendingRowStatus.SENT),
            SendingRow(SaleReference("b"), Money(4700, usd), SendingRowStatus.STILL_ON_TERMINAL),
        ),
        onTryAgain = {},
        onDone = {},
    )
}
