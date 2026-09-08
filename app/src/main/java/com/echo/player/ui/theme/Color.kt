package com.echo.player.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Neutral paper, ink, one hot accent. Nothing else.
 *
 * Both finishes are built from the same roles so every surface has a counterpart: the page, the
 * disc that sits on it, three weights of ink, and the accent. The greys are deliberately free of
 * warmth in either mode, so the accent stays the only colour on the screen.
 */
data class EchoPalette(
    /** The page behind everything. */
    val bg: Color,
    /** Faint ruling over the page. */
    val grid: Color,
    /** The disc face. */
    val disc: Color,
    val discEdge: Color,
    val discShadow: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    /** Hairline borders. */
    val hair: Color,
    /** Ring ticks not yet reached. */
    val tickOff: Color,
    val accent: Color,
    val accentPressed: Color,
    val isDark: Boolean
)

internal val LightPalette = EchoPalette(
    bg = Color(0xFFF2F2F3),
    grid = Color(0xFFE7E7E9),
    disc = Color(0xFFFFFFFF),
    discEdge = Color(0xFFEAEAEC),
    discShadow = Color(0x1A000000),
    ink = Color(0xFF0A0A0B),
    inkSoft = Color(0xFF87878C),
    inkFaint = Color(0xFFB4B4BA),
    hair = Color(0xFFE3E3E6),
    tickOff = Color(0xFFD5D5DA),
    accent = Color(0xFFEF3B12),
    accentPressed = Color(0xFFCC2F0B),
    isDark = false
)

/**
 * Not an inversion. The disc stays a raised card — lighter than the page, as it is in daylight —
 * and the accent is lifted slightly because a saturated red reads darker against black.
 */
internal val DarkPalette = EchoPalette(
    bg = Color(0xFF101011),
    grid = Color(0xFF1A1A1C),
    disc = Color(0xFF1D1D1F),
    discEdge = Color(0xFF2B2B2E),
    discShadow = Color(0x66000000),
    ink = Color(0xFFF3F3F4),
    inkSoft = Color(0xFF8E8E94),
    inkFaint = Color(0xFF5C5C62),
    hair = Color(0xFF2E2E32),
    tickOff = Color(0xFF3B3B40),
    accent = Color(0xFFFF4A20),
    accentPressed = Color(0xFFD8380F),
    isDark = true
)

internal val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * Reads as a constant at the call site — `Paper.Ink` — but resolves through the theme, so the
 * whole interface follows the system without every composable taking a colour parameter.
 *
 * These are composable getters, so they cannot be read inside a `DrawScope` or any other
 * non-composable lambda. Hoist them into a local first.
 */
object Paper {
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
    val Grid: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.grid
    val Disc: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.disc
    val DiscEdge: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.discEdge
    val DiscShadow: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.discShadow
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
    val InkSoft: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.inkSoft
    val InkFaint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.inkFaint
    val Hair: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.hair
    val TickOff: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.tickOff
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val AccentPressed: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.accentPressed
}

/** Material's scheme backs the components we keep (sheets, fields, snackbars). */
internal fun colorSchemeFor(palette: EchoPalette): ColorScheme = with(palette) {
    if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = disc,
            onPrimaryContainer = ink,
            secondary = ink,
            onSecondary = bg,
            secondaryContainer = hair,
            onSecondaryContainer = ink,
            background = bg,
            onBackground = ink,
            surface = disc,
            onSurface = ink,
            surfaceVariant = bg,
            onSurfaceVariant = inkSoft,
            surfaceContainer = disc,
            surfaceContainerHigh = disc,
            surfaceContainerHighest = bg,
            outline = hair,
            outlineVariant = tickOff,
            error = accent,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = disc,
            onPrimaryContainer = ink,
            secondary = ink,
            onSecondary = Color.White,
            secondaryContainer = hair,
            onSecondaryContainer = ink,
            background = bg,
            onBackground = ink,
            surface = disc,
            onSurface = ink,
            surfaceVariant = bg,
            onSurfaceVariant = inkSoft,
            surfaceContainer = disc,
            surfaceContainerHigh = disc,
            surfaceContainerHighest = bg,
            outline = hair,
            outlineVariant = tickOff,
            error = accent,
            onError = Color.White
        )
    }
}
