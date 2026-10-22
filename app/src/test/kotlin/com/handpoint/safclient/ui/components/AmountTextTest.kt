package com.handpoint.safclient.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.ui.theme.SafTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Currency

@RunWith(RobolectricTestRunner::class)
// Real text measurement; the legacy graphics shadows report near-zero glyph widths.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmountTextTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largestAmountShrinksToFitNarrowWidthWithoutClipping() {
        var layout: TextLayoutResult? = null
        compose.setContent {
            SafTheme(darkTheme = false) {
                Box(Modifier.width(200.dp)) {
                    AmountText(
                        money = Money(9_999_999, Currency.getInstance("USD")),
                        style = SafTheme.amounts.hero,
                        onTextLayout = { layout = it },
                    )
                }
            }
        }

        compose.onNodeWithText("$99,999.99").assertIsDisplayed()
        val result = checkNotNull(layout) { "no text layout reported" }
        assertFalse("amount overflows its box", result.hasVisualOverflow)
        assertTrue("font did not shrink", result.layoutInput.style.fontSize < HeroSize)
        assertTrue("font went below minimum", result.layoutInput.style.fontSize >= MinAmountFontSize)
    }

    private companion object {
        val HeroSize = 56.sp
    }
}
