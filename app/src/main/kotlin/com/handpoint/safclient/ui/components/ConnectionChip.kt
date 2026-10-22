package com.handpoint.safclient.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.ui.theme.PillShape
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme

/** Merchant-facing word for the terminal connection state. */
fun TerminalConnection.label(): String = when (this) {
    TerminalConnection.CONNECTED -> "Connected"
    TerminalConnection.CONNECTING -> "Connecting"
    TerminalConnection.DISCONNECTED -> "Disconnected"
    TerminalConnection.NOT_CONFIGURED -> "Not set up"
}

/** Small chip that shows whether the card reader is ready. The dot pulses while connecting. */
@Composable
fun ConnectionChip(connection: TerminalConnection, modifier: Modifier = Modifier) {
    val colors = SafTheme.colors
    val tone = when (connection) {
        TerminalConnection.CONNECTED -> colors.paid
        TerminalConnection.CONNECTING -> colors.processing
        TerminalConnection.DISCONNECTED -> colors.failed
        TerminalConnection.NOT_CONFIGURED -> colors.neutral
    }
    val dotAlpha = if (connection == TerminalConnection.CONNECTING) {
        rememberInfiniteTransition(label = "connecting").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
            label = "dotAlpha",
        ).value
    } else {
        1f
    }
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = "Terminal ${connection.label().lowercase()}"
            }
            .background(tone.container, PillShape)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(tone.content, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(connection.label(), color = tone.content, style = MaterialTheme.typography.labelMedium)
    }
}

@SafPreviews
@Composable
private fun ConnectionChipPreview() = SafPreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalConnection.entries.forEach { ConnectionChip(it) }
    }
}
