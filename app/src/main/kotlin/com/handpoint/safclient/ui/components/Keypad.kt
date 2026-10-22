package com.handpoint.safclient.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import com.handpoint.safclient.ui.theme.tabular

/** Height of one key. Well above the 56 dp minimum so a thumb on a terminal never misses. */
private val KeyHeight = 64.dp
private val KeyGap = 10.dp

/**
 * Amount keypad: 1–9, then 00, 0 and delete. Stateless: it only reports presses.
 * Every press gives a short haptic tick and a subtle press-down scale.
 */
@Composable
fun Keypad(
    onDigit: (Int) -> Unit,
    onDoubleZero: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            KeyRow {
                row.forEach { digit -> TextKey(digit.toString(), digit.toString()) { onDigit(digit) } }
            }
        }
        KeyRow {
            TextKey("00", "Double zero", onDoubleZero)
            TextKey("0", "0") { onDigit(0) }
            Key("Delete", onBackspace) {
                Icon(BackspaceIcon, contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KeyGap), content = content)
}

@Composable
private fun RowScope.TextKey(text: String, description: String, onClick: () -> Unit) {
    Key(description, onClick) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall.tabular().copy(fontSize = 26.sp),
        )
    }
}

@Composable
private fun RowScope.Key(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(stiffness = 1_400f),
        label = "keyScale",
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(KeyHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(MaterialTheme.shapes.medium)
            .background(SafTheme.colors.keySurface)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private val BackspaceIcon: ImageVector = ImageVector.Builder(
    name = "Backspace",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(
        "M9,5H20a2,2 0 0 1 2,2V17a2,2 0 0 1 -2,2H9L3,12Z M12.5,9.5L17.5,14.5 M17.5,9.5L12.5,14.5",
    ),
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 1.8f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()

@SafPreviews
@Composable
private fun KeypadPreview() = SafPreviewSurface {
    Keypad(onDigit = {}, onDoubleZero = {}, onBackspace = {})
}
