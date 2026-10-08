package com.btv.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing and sizes shared by every screen. Compose dp here are already
 * shrunk by APP_UI_SCALE (see Theme.kt): a 1080p Fire TV lays out about
 * 1170 x 660 dp, a 720p one the same, so these values hold on both.
 */
object BtvDimens {
    /** Page gutters (TV overscan safe). */
    val screenPaddingH = 40.dp
    val screenPaddingV = 26.dp
    /** Between page sections (header -> content, hero -> rail). */
    val sectionSpacing = 24.dp
    /** Between a title and the block it introduces. */
    val titleSpacing = 12.dp
    /** Between cards in a row or grid. */
    val cardSpacing = 16.dp
    /** Between rows of a list (channels, programs, settings). */
    val listSpacing = 6.dp

    val headerHeight = 52.dp
    val sidebarWidth = 228.dp
    val sidebarPadding = 16.dp

    val posterWidth = 124.dp
    /** Cinema poster, width:height. */
    const val POSTER_RATIO = 2f / 3f

    val controlHeight = 42.dp
    val searchHeight = 40.dp
    val iconButtonSize = 42.dp
    val iconSize = 20.dp

    /** Focus ring: thin, accent colored. */
    val focusBorder = 2.dp
    /** Hairline on resting surfaces that need an edge. */
    val hairline = 1.dp

    val miniPlayerWidth = 300.dp
    val miniPlayerHeight = 169.dp
}

/** Short, purposeful motion only: the UI must feel instant on a remote. */
object BtvMotion {
    /** Focus ring, fill and scale. */
    const val FOCUS_MS = 150
    /** Content swap (hero artwork, panels). */
    const val NAV_MS = 200
    /** Posters and home cards. */
    const val FOCUS_SCALE = 1.05f
    /** Buttons and rows: just enough to read as "lifted". */
    const val FOCUS_SCALE_SMALL = 1.03f
}
