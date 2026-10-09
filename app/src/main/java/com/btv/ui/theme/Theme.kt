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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Fire TV reports a large display density for the 1080p UI. Scale Compose
// dimensions and text together so every menu uses the same, more compact
// visual rhythm while full-screen surfaces still fill the display.
private const val APP_UI_SCALE = 0.82f

// Phones: what used to be the TV scale with "Très grande" text (0.82 x 1.3)
// is their "Normale" - the compact TV rhythm was too small in the hand.
private const val PHONE_UI_SCALE = 1.066f

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
    val accentTint: Color,
    /** The thin ring around whatever the remote has focused. */
    val focusRing: Color = Color.Unspecified,
    /** Text/icons drawn on a solid accent fill (primary button, badges). */
    val onAccent: Color = Color.White
)

val DarkPalette = BtvPalette(
    isLight = false,
    // "bTV Minimal Dark Streaming": near-black page, two lifted surfaces,
    // one hairline border, three text levels. Green is only ever an accent.
    bgBlack = Color(0xFF080909),
    bgApp = Color(0xFF080909),
    surface = Color(0xFF111313),
    surface2 = Color(0xFF181B1B),
    surface3 = Color(0xFF222626),
    border = Color(0xFF292D2D),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA7ADAA),
    // 5.2:1 on the page, 4.6:1 on a focused card: readable small text at 3 m.
    textMuted = Color(0xFF858C88),
    textFaint = Color(0xFF5E6562),
    overlaySoft = Color(0x0FFFFFFF),
    overlayMedium = Color(0x1FFFFFFF),
    // Accent fields are filled in by [withAccent].
    accentOnSurface = Color.Unspecified,
    accentTint = Color.Unspecified
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
    accentOnSurface = Color.Unspecified,
    accentTint = Color.Unspecified
)

private fun BtvPalette.withAccent(accent: AccentColor) = copy(
    accentOnSurface = if (isLight) accent.dark else accent.bright,
    accentTint = if (isLight) accent.tintLight else accent.tintDark,
    focusRing = if (isLight) accent.dark else accent.main,
    // A light accent (the bTV green) reads better with near-black text, a dark one with white.
    onAccent = if (accent.main.luminance() > 0.25f) Color(0xFF04120A) else Color.White
)

private val LocalBtvPalette = staticCompositionLocalOf { DarkPalette }

/**
 * True on a TV / TV box (remote), false on a phone or tablet (touch).
 * Touch-only helpers (PIN keypad, hints) read it; the remote paths never do.
 */
val LocalIsTv = staticCompositionLocalOf { true }

/**
 * The touch layouts (grid, sheets, lists without side panel): a phone in
 * either orientation - its landscape is far too short for the TV layouts -
 * and a tablet held upright. A TV, or a tablet held sideways, keeps the TV ones.
 */
@Composable
fun useTouchLayout(): Boolean {
    if (LocalIsTv.current) return false
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT ||
        configuration.smallestScreenWidthDp < 600
}

/** UI zoom in effect: compact on TVs and phones, natural size on tablets. */
private val LocalUiScale = staticCompositionLocalOf { APP_UI_SCALE }

/** Text-size zoom of the UI only (Tizen applyTextSize); the player resets it. */
private val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

private fun darkColors(accent: AccentColor) = darkColorScheme(
    primary = accent.main,
    secondary = accent.dark,
    background = BtvBlack,
    surface = BtvSurface,
    surfaceVariant = DarkPalette.surface2,
    outline = DarkPalette.border,
    onPrimary = BtvWhite,
    onBackground = BtvWhite,
    onSurface = BtvWhite
)

private fun lightColors(accent: AccentColor) = lightColorScheme(
    primary = accent.main,
    secondary = accent.dark,
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
    accent: AccentColor = AccentColor.GREEN,
    isTv: Boolean = true,
    content: @Composable () -> Unit
) {
    // Global, not a CompositionLocal: BtvGreen is read from plain code too.
    if (BtvAccent.current != accent) BtvAccent.current = accent
    val deviceDensity = LocalDensity.current
    val baseDensity = LocalBaseDensity.current ?: deviceDensity
    // TVs (seen from the couch) keep the compact scale; phones get a larger
    // one for the hand; a tablet uses the natural size.
    val smallestWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp
    val uiScale = when {
        isTv -> APP_UI_SCALE
        smallestWidthDp < 600 -> PHONE_UI_SCALE
        else -> 1f
    }
    val uiDensity = remember(baseDensity.density, baseDensity.fontScale, textScale, uiScale) {
        Density(baseDensity.density * uiScale * textScale, baseDensity.fontScale)
    }
    CompositionLocalProvider(
        LocalIsTv provides isTv,
        LocalUiScale provides uiScale,
        LocalBaseDensity provides baseDensity,
        LocalDensity provides uiDensity,
        LocalBtvPalette provides (if (darkTheme) DarkPalette else LightPalette).withAccent(accent)
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkColors(accent) else lightColors(accent),
            typography = BtvMaterialTypography,
            content = content
        )
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
    val uiScale = LocalUiScale.current
    val playerDensity = remember(baseDensity.density, baseDensity.fontScale, uiScale) {
        Density(baseDensity.density * uiScale, baseDensity.fontScale)
    }
    val accent = BtvAccent.current
    CompositionLocalProvider(
        LocalDensity provides playerDensity,
        LocalBtvPalette provides DarkPalette.withAccent(accent)
    ) {
        MaterialTheme(colorScheme = darkColors(accent), typography = BtvMaterialTypography, content = content)
    }
}
