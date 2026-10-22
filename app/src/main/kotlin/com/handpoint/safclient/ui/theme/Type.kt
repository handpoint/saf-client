package com.handpoint.safclient.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Base = Typography()

private fun TextStyle.system(weight: FontWeight? = fontWeight) =
    copy(fontFamily = FontFamily.Default, fontWeight = weight)

/** Material 3 type scale on the system font, with firmer weights for titles and labels. */
internal val SafTypography = Typography(
    displayLarge = Base.displayLarge.system(),
    displayMedium = Base.displayMedium.system(),
    displaySmall = Base.displaySmall.system(),
    headlineLarge = Base.headlineLarge.system(FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.system(FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.system(FontWeight.SemiBold),
    titleLarge = Base.titleLarge.system(FontWeight.SemiBold),
    titleMedium = Base.titleMedium.system(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.system(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.system(),
    bodyMedium = Base.bodyMedium.system(),
    bodySmall = Base.bodySmall.system(),
    labelLarge = Base.labelLarge.system(FontWeight.SemiBold),
    labelMedium = Base.labelMedium.system(FontWeight.SemiBold),
    labelSmall = Base.labelSmall.system(FontWeight.SemiBold).copy(letterSpacing = 0.06.em),
)

private const val TABULAR_FIGURES = "tnum"

/** Amount styles. All use tabular figures so digits do not shift while an amount changes. */
@Immutable
data class AmountTypography(
    /** The amount being keyed in or charged. */
    val hero: TextStyle,
    /** Totals on cards and result screens. */
    val large: TextStyle,
    /** Amounts in lists. */
    val row: TextStyle,
)

internal val SafAmountTypography = AmountTypography(
    hero = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    large = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.015).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    row = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
)

/** Returns this style with tabular figures. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)
