package com.handpoint.safclient.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Light palette (approved direction B).
internal val Canvas = Color(0xFFF6F7F9)
internal val CardWhite = Color(0xFFFFFFFF)
internal val Ink = Color(0xFF111827)
internal val InkMuted = Color(0xFF6B7280)
internal val Hairline = Color(0xFFE5E7EB)
internal val SurfaceSunken = Color(0xFFEEF0F3)
internal val Accent = Color(0xFF1F4FD8)
internal val AccentContainer = Color(0xFFE6ECFC)
internal val ErrorLight = Color(0xFFB42335)
internal val ErrorContainerLight = Color(0xFFFBE5E8)

// Dark palette: same roles, tuned so text on every surface and pill stays at or above WCAG AA.
internal val CanvasDark = Color(0xFF0D1117)
internal val CardDark = Color(0xFF161B22)
internal val InkDark = Color(0xFFF3F4F6)
internal val InkMutedDark = Color(0xFF9CA3AF)
internal val HairlineDark = Color(0xFF30363D)
internal val SurfaceSunkenDark = Color(0xFF21262D)
internal val AccentDark = Color(0xFF7FA2FF)
internal val AccentContainerDark = Color(0xFF16244A)
internal val ErrorDark = Color(0xFFFF8A98)
internal val ErrorContainerDark = Color(0xFF42141B)

/** Foreground and background of one status tone (pills, chips, badges). */
@Immutable
data class Tone(val content: Color, val container: Color)

/** Colours outside the Material scheme that the design system needs. */
@Immutable
data class SafColors(
    val stored: Tone,
    val paid: Tone,
    val processing: Tone,
    val failed: Tone,
    val neutral: Tone,
    val textMuted: Color,
    val hairline: Color,
    val keySurface: Color,
    val accent: Color,
)

internal val LightSafColors = SafColors(
    stored = Tone(content = Color(0xFF8A5300), container = Color(0xFFFFF1DC)),
    paid = Tone(content = Color(0xFF13683F), container = Color(0xFFE1F5EA)),
    processing = Tone(content = Accent, container = AccentContainer),
    failed = Tone(content = ErrorLight, container = ErrorContainerLight),
    neutral = Tone(content = Color(0xFF374151), container = SurfaceSunken),
    textMuted = InkMuted,
    hairline = Hairline,
    keySurface = CardWhite,
    accent = Accent,
)

internal val DarkSafColors = SafColors(
    stored = Tone(content = Color(0xFFF5B65A), container = Color(0xFF3A2A10)),
    paid = Tone(content = Color(0xFF5CD394), container = Color(0xFF0F3322)),
    processing = Tone(content = AccentDark, container = AccentContainerDark),
    failed = Tone(content = ErrorDark, container = ErrorContainerDark),
    neutral = Tone(content = Color(0xFFC9D1D9), container = SurfaceSunkenDark),
    textMuted = InkMutedDark,
    hairline = HairlineDark,
    keySurface = CardDark,
    accent = AccentDark,
)
