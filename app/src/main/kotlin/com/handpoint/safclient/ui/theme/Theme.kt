package com.handpoint.safclient.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = SurfaceSunken,
    onPrimaryContainer = Ink,
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = AccentContainer,
    onSecondaryContainer = Accent,
    tertiary = Accent,
    onTertiary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = SurfaceSunken,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = CardWhite,
    surfaceContainerLow = CardWhite,
    surfaceContainer = CardWhite,
    surfaceContainerHigh = CardWhite,
    surfaceContainerHighest = SurfaceSunken,
    outline = Color(0xFFD1D5DB),
    outlineVariant = Hairline,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = ErrorLight,
    inverseSurface = Ink,
    inverseOnSurface = Canvas,
    scrim = Color.Black,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = InkDark,
    onPrimary = CanvasDark,
    primaryContainer = SurfaceSunkenDark,
    onPrimaryContainer = InkDark,
    secondary = AccentDark,
    onSecondary = CanvasDark,
    secondaryContainer = AccentContainerDark,
    onSecondaryContainer = AccentDark,
    tertiary = AccentDark,
    onTertiary = CanvasDark,
    background = CanvasDark,
    onBackground = InkDark,
    surface = CanvasDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceSunkenDark,
    onSurfaceVariant = InkMutedDark,
    surfaceContainerLowest = CanvasDark,
    surfaceContainerLow = CardDark,
    surfaceContainer = CardDark,
    surfaceContainerHigh = CardDark,
    surfaceContainerHighest = SurfaceSunkenDark,
    outline = Color(0xFF484F58),
    outlineVariant = HairlineDark,
    error = ErrorDark,
    onError = CanvasDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorDark,
    inverseSurface = InkDark,
    inverseOnSurface = CanvasDark,
    scrim = Color.Black,
)

private val LocalSafColors = staticCompositionLocalOf { LightSafColors }
private val LocalAmountTypography = staticCompositionLocalOf { SafAmountTypography }

/**
 * The app theme: Material 3 with the approved neutral palette, plus the status tones and
 * amount styles that Material does not model (see [SafTheme.colors] and [SafTheme.amounts]).
 */
@Composable
fun SafTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSafColors provides if (darkTheme) DarkSafColors else LightSafColors,
        LocalAmountTypography provides SafAmountTypography,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = SafTypography,
            shapes = SafShapes,
            content = content,
        )
    }
}

/** Design-system values that live outside [MaterialTheme]. */
object SafTheme {
    val colors: SafColors
        @Composable @ReadOnlyComposable get() = LocalSafColors.current

    val amounts: AmountTypography
        @Composable @ReadOnlyComposable get() = LocalAmountTypography.current
}

/** Light and dark previews of one component; apply to a preview function. */
@Preview(name = "Light", showBackground = true, backgroundColor = 0xFFF6F7F9)
@Preview(
    name = "Dark",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
annotation class SafPreviews

/** Wraps preview content in the theme on the canvas colour, with a little padding. */
@Composable
internal fun SafPreviewSurface(content: @Composable () -> Unit) {
    SafTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) { content() }
        }
    }
}
