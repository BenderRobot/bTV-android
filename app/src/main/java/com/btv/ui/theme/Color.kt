package com.btv.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The app's accent, chosen in Réglages > Affichage. Every highlight reads
 * it: focus borders, selected tiles, progress bars, "En cours" labels...
 *
 * @param main fills and focus borders (white text on it).
 * @param dark accent text/icons on a light surface.
 * @param bright accent text, fills and progress on a dark surface.
 * @param tintDark / tintLight background of a focused, accent-tinted button.
 */
enum class AccentColor(
    val key: String,
    val label: String,
    val main: Color,
    val dark: Color,
    val bright: Color,
    val tintDark: Color,
    val tintLight: Color
) {
    // Matched to the app icon: its tile gradient (#038228 -> #035E1C) and its bright outline.
    GREEN("green", "Vert bTV", Color(0xFF038228), Color(0xFF036821), Color(0xFF4FD63C), Color(0xFF1E3F26), Color(0xFFD3F0DC)),
    BLUE("blue", "Bleu", Color(0xFF1E6FD9), Color(0xFF1558B0), Color(0xFF5AA9FF), Color(0xFF1D3350), Color(0xFFD6E6FA)),
    PURPLE("purple", "Violet", Color(0xFF7B3FE4), Color(0xFF5E2DB8), Color(0xFFB48CFF), Color(0xFF34254F), Color(0xFFE6DCFA)),
    RED("red", "Rouge", Color(0xFFD32F2F), Color(0xFFA82424), Color(0xFFFF6B6B), Color(0xFF4A2222), Color(0xFFF8D7D7)),
    ORANGE("orange", "Orange", Color(0xFFE07A10), Color(0xFFB5600A), Color(0xFFFFAA4D), Color(0xFF4A3519), Color(0xFFFBE5CC)),
    CYAN("cyan", "Cyan", Color(0xFF0097A7), Color(0xFF00737F), Color(0xFF4DD9E8), Color(0xFF17414A), Color(0xFFCDEFF3)),
    PINK("pink", "Rose", Color(0xFFD81B60), Color(0xFFAD1457), Color(0xFFFF6FA3), Color(0xFF4A1F33), Color(0xFFF9D5E3));

    companion object {
        fun fromKey(key: String?): AccentColor = entries.firstOrNull { it.key == key } ?: GREEN
    }
}

/**
 * The accent in effect. Snapshot state, so every composable that reads
 * [BtvGreen] & co. recomposes when the user picks another color - including
 * code outside a @Composable scope that only needs the current value.
 */
object BtvAccent {
    var current by mutableStateOf(AccentColor.GREEN)
}

// Historic names kept: "green" now means "the accent", whatever its color.
val BtvGreen: Color get() = BtvAccent.current.main
val BtvGreenDark: Color get() = BtvAccent.current.dark
// Tizen's --accent-bright (style.css): used for "is-fav"/"is-watched"
// active states, progress fills, and other bright highlights on top of a
// dark surface - the main accent alone reads as too dim for those.
val BtvGreenBright: Color get() = BtvAccent.current.bright
val BtvBlack = Color(0xFF060606)
val BtvSurface = Color(0xFF1A1A1A)
val BtvSurfaceLight = Color(0xFFF2F2F2)
val BtvWhite = Color(0xFFFFFFFF)
val BtvBlackText = Color(0xFF111111)
