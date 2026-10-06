package com.btv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Fire TV reports a large display density for the 1080p UI. Scale Compose
// dimensions and text together so every menu uses the same, more compact
// visual rhythm while full-screen surfaces still fill the display.
private const val APP_UI_SCALE = 0.82f

/**
 * Port of Tizen's CSS variables (css/style.css :root and body.theme-light).
 * Screens read these instead of literal colors so the light theme reaches
 * them. Text drawn over video or artwork keeps literal white/black scrims.
 */
@Immutable
data class BtvPalette(
    val isLight: Boolean,
    val bgBlack: Color,
    val bgApp: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textFaint: Color,
    val overlaySoft: Color,
    val overlayMedium: Color,
    /** Accent for text/icons on a surface (--accent-on-surface). */
    val accentOnSurface: Color,
    /** Background of a focused, accent-tinted button. */
    val accentTint: Color
)

val DarkPalette = BtvPalette(
    isLight = false,
    bgBlack = Color(0xFF060606),
    bgApp = Color(0xFF0C0C0C),
    surface = Color(0xFF1A1A1A),
    surface2 = Color(0xFF262626),
    surface3 = Color(0xFF333333),
    border = Color(0xFF333333),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFC7C7C7),
    textMuted = Color(0xFF8A8A8A),
    textFaint = Color(0xFF666666),
    overlaySoft = Color(0x14FFFFFF),
    overlayMedium = Color(0x29FFFFFF),
    accentOnSurface = BtvGreenBright,
    accentTint = Color(0xFF25462D)
)

val LightPalette = BtvPalette(
    isLight = true,
    bgBlack = Color(0xFFE7E7EA),
    bgApp = Color(0xFFF4F4F5),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFECECEC),
    surface3 = Color(0xFFDCDCDC),
    border = Color(0xFFD5D5D8),
    textPrimary = Color(0xFF111111),
    textSecondary = Color(0xFF333333),
    textMuted = Color(0xFF555555),
    textFaint = Color(0xFF777777),
    overlaySoft = Color(0x0D000000),
    overlayMedium = Color(0x17000000),
    accentOnSurface = BtvGreenDark,
    accentTint = Color(0xFFD3F0DC)
)

private val LocalBtvPalette = staticCompositionLocalOf { DarkPalette }

/** Text-size zoom of the UI only (Tizen applyTextSize); the player resets it. */
private val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

private val DarkColors = darkColorScheme(
    primary = BtvGreen,
    secondary = BtvGreenDark,
    background = BtvBlack,
    surface = BtvSurface,
    onPrimary = BtvWhite,
    onBackground = BtvWhite,
    onSurface = BtvWhite
)

private val LightColors = lightColorScheme(
    primary = BtvGreen,
    secondary = BtvGreenDark,
    background = LightPalette.bgApp,
    surface = LightPalette.surface,
    onPrimary = BtvWhite,
    onBackground = LightPalette.textPrimary,
    onSurface = LightPalette.textPrimary
)

/** Tizen TEXT_SIZE_OPTIONS (js/app-shell.js), stored as a percentage. */
val TEXT_SIZE_PERCENT_OPTIONS = listOf(85 to "Petite", 100 to "Normale", 115 to "Grande", 130 to "Très grande")

object BtvTheme {
    val colors: BtvPalette
        @Composable @ReadOnlyComposable
        get() = LocalBtvPalette.current
}

/**
 * Dark by default, like Tizen. [textScale] zooms every dimension and text of
 * the UI together, as Tizen's CSS zoom does (its layout is in fixed px too).
 */
@Composable
fun BtvTheme(
    darkTheme: Boolean = true,
    textScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val deviceDensity = LocalDensity.current
    val baseDensity = LocalBaseDensity.current ?: deviceDensity
    val uiDensity = remember(baseDensity.density, baseDensity.fontScale, textScale) {
        Density(baseDensity.density * APP_UI_SCALE * textScale, baseDensity.fontScale)
    }
    CompositionLocalProvider(
        LocalBaseDensity provides baseDensity,
        LocalDensity provides uiDensity,
        LocalBtvPalette provides if (darkTheme) DarkPalette else LightPalette
    ) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}

/**
 * The player stays as it was regardless of the display settings: always
 * dark (text over video) and unaffected by the text-size zoom, like Tizen's
 * counter-zoom on #player-view.
 */
@Composable
fun PlayerSurfaceTheme(content: @Composable () -> Unit) {
    val baseDensity = LocalBaseDensity.current ?: LocalDensity.current
    val playerDensity = remember(baseDensity.density, baseDensity.fontScale) {
        Density(baseDensity.density * APP_UI_SCALE, baseDensity.fontScale)
    }
    CompositionLocalProvider(
        LocalDensity provides playerDensity,
        LocalBtvPalette provides DarkPalette
    ) {
        MaterialTheme(colorScheme = DarkColors, content = content)
    }
}
