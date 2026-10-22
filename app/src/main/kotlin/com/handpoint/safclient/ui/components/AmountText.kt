package com.handpoint.safclient.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import com.handpoint.safclient.ui.theme.tabular
import java.util.Currency

/** Smallest size an amount may shrink to before it fits; below this it stops being glanceable. */
val MinAmountFontSize = 28.sp

/**
 * A formatted amount on one line. Tabular figures are always applied, so digits keep their
 * width while the amount changes. Pick a size from [SafTheme.amounts].
 *
 * Money is never clipped: when the amount does not fit the width, the font steps down from
 * the style size towards [MinAmountFontSize] (or the style size, if that is smaller).
 */
@Composable
fun AmountText(
    money: Money,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val resolvedColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val maxSize = style.fontSize
    val autoSize = if (maxSize.isSp) {
        val minSize = if (maxSize < MinAmountFontSize) maxSize else MinAmountFontSize
        TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 1.sp)
    } else {
        null
    }
    BasicText(
        text = money.format(),
        modifier = modifier,
        style = style.tabular().copy(color = resolvedColor),
        onTextLayout = onTextLayout,
        maxLines = 1,
        softWrap = false,
        autoSize = autoSize,
    )
}

@SafPreviews
@Composable
private fun AmountTextPreview() = SafPreviewSurface {
    val usd = Currency.getInstance("USD")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AmountText(Money(1250, usd), SafTheme.amounts.hero)
        AmountText(Money(8450, usd), SafTheme.amounts.large)
        AmountText(Money(111111, usd), SafTheme.amounts.row)
        Box(Modifier.width(200.dp)) { AmountText(Money(9999999, usd), SafTheme.amounts.hero) }
    }
}
