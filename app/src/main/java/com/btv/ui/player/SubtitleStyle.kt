package com.btv.ui.player

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.btv.data.store.PreferencesStore
import com.btv.data.store.SubtitleStylePrefs

/**
 * Port of Tizen's SUBTITLE_*_OPTIONS (js/data.js) and applySubtitleStylePrefs
 * (js/player.js). Tizen's web fonts (Segoe UI, Georgia...) do not exist on
 * Android, so the five choices map to the system families instead. Sizes are
 * fractions of the video height around Media3's default (0.0533, "Normale"),
 * since Tizen's CSS pixels have no direct equivalent in a PlayerView.
 */
object SubtitleStyleOptions {
    data class Option<T>(val label: String, val value: T)

    val fonts = listOf(
        Option("Sans serif", Typeface.SANS_SERIF),
        Option("Sans serif gras", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)),
        Option("Condensée", Typeface.create("sans-serif-condensed", Typeface.NORMAL)),
        Option("Serif", Typeface.SERIF),
        Option("Monospace", Typeface.MONOSPACE)
    )
    val colors = listOf(
        Option("Blanc", 0xFFFFFFFF.toInt()),
        Option("Jaune", 0xFFFFE14D.toInt()),
        Option("Cyan", 0xFF4DD8FF.toInt()),
        Option("Vert", 0xFF4CDA3E.toInt())
    )
    val backgrounds = listOf(
        Option("Semi-transparent", 0x99000000.toInt()),
        Option("Opaque", 0xF2000000.toInt()),
        Option("Aucun", 0x00000000)
    )
    val sizes = listOf(
        Option("Petite", 0.042f),
        Option("Normale", 0.0533f), // SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
        Option("Grande", 0.065f),
        Option("Très grande", 0.078f)
    )

    fun font(prefs: SubtitleStylePrefs) = fonts[prefs.fontIndex.mod(fonts.size)]
    fun color(prefs: SubtitleStylePrefs) = colors[prefs.colorIndex.mod(colors.size)]
    fun background(prefs: SubtitleStylePrefs) = backgrounds[prefs.backgroundIndex.mod(backgrounds.size)]
    fun size(prefs: SubtitleStylePrefs) = sizes[prefs.sizeIndex.mod(sizes.size)]
}

/** The chosen style replaces the stream's own cue styling, like Tizen's ::cue override. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun SubtitleView.applyStylePrefs(prefs: SubtitleStylePrefs) {
    val background = SubtitleStyleOptions.background(prefs).value
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(false)
    setStyle(
        CaptionStyleCompat(
            SubtitleStyleOptions.color(prefs).value,
            background,
            0x00000000,
            // Without a background box, a shadow keeps white text readable on bright scenes.
            if (background == 0) CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else CaptionStyleCompat.EDGE_TYPE_NONE,
            0xFF000000.toInt(),
            SubtitleStyleOptions.font(prefs).value
        )
    )
    setFractionalTextSize(SubtitleStyleOptions.size(prefs).value)
}

/** Live subtitle style: a change in Réglages applies to a playing video right away. */
@Composable
fun rememberSubtitleStylePrefs(): State<SubtitleStylePrefs> {
    val context = LocalContext.current.applicationContext
    val flow = remember(context) { PreferencesStore(context).subtitleStyle }
    return flow.collectAsState(initial = SubtitleStylePrefs())
}
