package com.handpoint.safclient.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import com.handpoint.safclient.ui.theme.tabular
import java.util.Currency

/**
 * A formatted amount on one line. Tabular figures are always applied, so digits keep their
 * width while the amount changes. Pick a size from [SafTheme.amounts].
 */
@Composable
fun AmountText(
    money: Money,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text = money.format(),
        modifier = modifier,
        style = style.tabular(),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
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
    }
}
