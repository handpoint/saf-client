package com.handpoint.safclient.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.ui.theme.PillShape
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import com.handpoint.safclient.ui.theme.Tone

/** Merchant-facing word for a processing status. */
fun ProcessingStatus.label(): String = when (this) {
    ProcessingStatus.STORED -> "Stored"
    ProcessingStatus.IN_PROGRESS -> "Processing"
    ProcessingStatus.SUCCEEDED -> "Paid"
    ProcessingStatus.FAILED -> "Failed"
    ProcessingStatus.CAPTURED -> "Captured"
    ProcessingStatus.REFUNDED -> "Refunded"
    ProcessingStatus.CANCELLED -> "Cancelled"
    ProcessingStatus.NOT_FOUND -> "Unknown"
}

@Composable
private fun ProcessingStatus.tone(): Tone {
    val colors = SafTheme.colors
    return when (this) {
        ProcessingStatus.STORED -> colors.stored
        ProcessingStatus.IN_PROGRESS -> colors.processing
        ProcessingStatus.SUCCEEDED, ProcessingStatus.CAPTURED -> colors.paid
        ProcessingStatus.FAILED -> colors.failed
        ProcessingStatus.REFUNDED, ProcessingStatus.CANCELLED, ProcessingStatus.NOT_FOUND -> colors.neutral
    }
}

/**
 * Compact status badge: a tinted pill with a dot and the status label.
 * Colour is never the only signal; the label is always shown and read out.
 */
@Composable
fun StatusPill(status: ProcessingStatus, modifier: Modifier = Modifier) {
    val tone = status.tone()
    val content by animateColorAsState(tone.content, label = "pillContent")
    val container by animateColorAsState(tone.container, label = "pillContainer")
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .background(container, PillShape)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(6.dp).background(content, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text = status.label(), color = content, style = MaterialTheme.typography.labelMedium)
    }
}

@SafPreviews
@Composable
private fun StatusPillPreview() = SafPreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProcessingStatus.entries.forEach { StatusPill(it) }
    }
}
