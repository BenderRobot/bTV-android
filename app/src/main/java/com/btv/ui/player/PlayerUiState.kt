package com.btv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player

// Port of Tizen's player.js osdZone: which part of the OSD currently has
// focus once it's showing.
enum class OsdZone { SEEK, BUTTONS, EPISODES }

// Port of Tizen's PLAYER_BUTTONS (js/player.js) - order matters, it's the
// Left/Right traversal order of the control row.
enum class PlayerButton { REWIND, PLAYPAUSE, FORWARD, NEXT, AUDIO, SUBTITLE, QUALITY, PIP }
val PLAYER_BUTTONS = listOf(
    PlayerButton.REWIND, PlayerButton.PLAYPAUSE, PlayerButton.FORWARD,
    PlayerButton.NEXT, PlayerButton.AUDIO, PlayerButton.SUBTITLE, PlayerButton.PIP
)
private val PLAYER_BUTTONS_WITH_QUALITY = PLAYER_BUTTONS.filter { it != PlayerButton.PIP } +
    PlayerButton.QUALITY + PlayerButton.PIP

enum class TrackMenuType { AUDIO, SUBTITLE, QUALITY }

data class TrackOption(val id: String?, val label: String, val isSelected: Boolean, val language: String? = null)

/** One entry in the zap/playlist drawer (same category rail the item was opened from). */
data class ZapItem(val id: String, val name: String, val posterUrl: String?, val streamUrl: String?)

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = C.TIME_UNSET,
    val bufferedPosition: Long = 0L,
    val playbackState: Int = Player.STATE_IDLE,
    val isLoading: Boolean = false,
    val isSeekable: Boolean = false,
    val isLive: Boolean = false,
    // Catch-up program: position and duration are the program's, whatever segment plays.
    val isReplay: Boolean = false,
    val canPause: Boolean = true,
    val canPlayPause: Boolean = true,
    val contentType: String = "video/mp4",
    val streamUrl: String = "",
    val contentName: String = "",
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,

    // Live robustness: the stream is being re-established behind a still
    // playing (or just drained) buffer, possibly on a sibling quality.
    val isReconnecting: Boolean = false,
    // Holding the first frame while a few seconds of live stream pile up.
    val isPrebuffering: Boolean = false,
    // Name of the sibling quality currently playing when it isn't the one picked.
    val liveFallbackName: String? = null,
    // Every quality of the live channel, best first (OSD "Qualité" menu).
    val liveQualities: List<TrackOption> = emptyList(),
    // Live guide: what the channel airs now (OSD), and per zap-list channel id (drawer).
    val liveNowPlaying: LiveProgram? = null,
    val zapPrograms: Map<String, LiveProgram> = emptyMap(),

    // OSD (port of Tizen's playerNav/osdZone, js/player.js)
    val osdVisible: Boolean = false,
    val osdZone: OsdZone = OsdZone.BUTTONS,
    val focusedButtonIndex: Int = PLAYER_BUTTONS.indexOf(PlayerButton.PLAYPAUSE),
    val flashMessage: String? = null,

    // Zap/playlist drawer (Down from the button row - js/player.js openEpisodeList)
    val zapList: List<ZapItem> = emptyList(),
    val zapIndex: Int = -1,
    val episodeFocusIndex: Int = 0,

    // Set only for series episodes - drives auto-advance to the next
    // episode/season on playback end (js/player.js handlePlaybackEnded).
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonNum: Int? = null,

    // Exit/mini-player confirmation (Back with OSD already hidden)
    val showExitDialog: Boolean = false,
    val exitDialogFocusIndex: Int = 0, // 0 = Réduire (mini-lecteur), 1 = Sortir

    // Audio/subtitle track picker
    val trackMenuType: TrackMenuType? = null,
    val trackMenuFocusIndex: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val currentAudioLabel: String = "",
    val currentSubtitleLabel: String = "Désactivés",

    // Mini-player (persistent across navigation - see PlayerHost)
    val isMiniPlayer: Boolean = false
) {
    /** The OSD row: "Qualité" only appears on a live channel that has siblings. */
    val playerButtons: List<PlayerButton>
        get() = if (liveQualities.size > 1) PLAYER_BUTTONS_WITH_QUALITY else PLAYER_BUTTONS

    val currentQualityLabel: String
        get() = liveQualities.firstOrNull { it.isSelected }?.label ?: "Qualité"

    fun trackMenuOptions(type: TrackMenuType? = trackMenuType): List<TrackOption> = when (type) {
        TrackMenuType.AUDIO -> audioTracks
        TrackMenuType.SUBTITLE -> subtitleTracks
        TrackMenuType.QUALITY -> liveQualities
        null -> emptyList()
    }
}
