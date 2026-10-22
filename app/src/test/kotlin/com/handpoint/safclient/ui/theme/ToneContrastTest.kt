package com.handpoint.safclient.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Every pill tone must keep its label readable: WCAG AA, contrast of at least 4.5:1. */
class ToneContrastTest {

    @Test
    fun lightTonesMeetWcagAa() = assertTones("light", LightSafColors)

    @Test
    fun darkTonesMeetWcagAa() = assertTones("dark", DarkSafColors)

    private fun assertTones(theme: String, colors: SafColors) {
        mapOf(
            "stored" to colors.stored,
            "paid" to colors.paid,
            "processing" to colors.processing,
            "failed" to colors.failed,
            "neutral" to colors.neutral,
        ).forEach { (name, tone) ->
            val ratio = contrast(tone.content, tone.container)
            assertTrue("$theme $name contrast is $ratio, below 4.5", ratio >= 4.5)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double =
            if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
