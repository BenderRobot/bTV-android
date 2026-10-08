package com.btv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.btv.data.store.LiveQualityChoice
import com.btv.data.store.PreferencesStore
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Activity-scoped (not route-scoped, see PlayerScreen/MainActivity): the
 * same instance - and the same underlying ExoPlayer - must survive
 * navigating back to Browse for the mini-player to actually keep playing
 * underneath, exactly like Tizen's approach of reparenting the same
 * <video>/AVPlay element rather than tearing it down (js/player.js
 * enterMiniPlayer/expandMiniPlayerToFullscreen).
 *
 * The whole OSD state machine below is a direct port of js/player.js +
 * js/input.js's player-view key handling - see PLAYER_BEHAVIOR.md and the
 * Tizen source for the exact table this follows.
 */
private const val LIVE_TAG = "BtvLive"
private const val REPLAY_TAG = "BtvReplay"

class PlayerViewModel(
    val player: ExoPlayer?,
    private val getPlaybackProgressUseCase: GetPlaybackProgressUseCase? = null,
    private var fetchSeriesEpisodes: (suspend (seriesId: String) -> SeriesEpisodesBySeason?)? = null,
    private val getRecentlyWatchedUseCase: GetRecentlyWatchedUseCase? = null,
    private val mediaSession: MediaSession? = null,
    private val trackPreferenceRepository: com.btv.data.repository.TrackPreferenceRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    // The real Xtream stream id + section tag (VOD/SERIES/LIVE, matching
    // ContentType.name) - NOT the raw playback URL/MIME type. Mutable
    // because switching zap-list items reassigns "what's currently
    // playing" without recreating the ViewModel (Tizen: currentPlayItem/
    // currentPlaySection reassignment, js/browse.js selectEpisodeListItem).
    private var contentId: String? = null
    private var progressType: String = "VOD"

    private var lastProgressSaveAt = 0L
    private val progressSaveIntervalMs = 10_000L
    private val progressSaveMutex = Mutex()
    private val historyWriteMutex = Mutex()
    private var playbackGeneration = 0L
    private var activePlayback = false
    private var appInBackground = false
    private var backgroundedAtMs = 0L
    private var retryJob: Job? = null
    private var resumeCheckJob: Job? = null
    private var historyRecordedGeneration = -1L
    private var historyCategoryId = ""
    private var historyCategoryName = ""
    private var historyPosterUrl: String? = null

    data class ResumePrompt(val resumePositionMs: Long)
    private val _resumePrompt = MutableStateFlow<ResumePrompt?>(null)
    val resumePrompt: StateFlow<ResumePrompt?> = _resumePrompt

    data class NextSeasonPrompt(val seasonNum: Int, val episodes: List<ZapItem>)
    private val _nextSeasonPrompt = MutableStateFlow<NextSeasonPrompt?>(null)
    val nextSeasonPrompt: StateFlow<NextSeasonPrompt?> = _nextSeasonPrompt
    private var nextSeasonCheckToken = 0
    private var zapListRebuildToken = 0
    private var nextSeasonCheckJob: Job? = null
    private var zapListRebuildJob: Job? = null

    // One-shot navigation requests: the ViewModel is Activity-scoped and
    // shouldn't hold NavController-derived closures (they'd go stale across
    // recomposition/navigation) - PlayerScreen observes these and performs
    // the actual navigation, then acknowledges via consume*().
    private val _miniPlayerRequested = MutableStateFlow(false)
    val miniPlayerRequested: StateFlow<Boolean> = _miniPlayerRequested
    private val _exitRequested = MutableStateFlow(false)
    val exitRequested: StateFlow<Boolean> = _exitRequested

    fun consumeMiniPlayerRequest() {
        _miniPlayerRequested.value = false
    }

    fun consumeExitRequest() {
        _exitRequested.value = false
    }

    fun setMiniPlayerActive(active: Boolean) {
        _uiState.update { it.copy(isMiniPlayer = active) }
    }

    /** Refresh the Activity-owned session source after recreation or account change. */
    fun setSeriesEpisodeLoader(loader: (suspend (seriesId: String) -> SeriesEpisodesBySeason?)?) {
        fetchSeriesEpisodes = loader
    }

    // --- OSD auto-hide (js/player.js resetPlayerHideTimer, 4000ms) ---
    private var hideTimerJob: Job? = null
    private var flashMessageJob: Job? = null

    // --- Seek acceleration (js/player.js SEEK_HOLD_RESET_MS/STEPS) ---
    private var lastSeekDirection = 0
    private var lastSeekAt = 0L
    private var seekStreak = 0
    private val seekStepsSeconds = listOf(10, 10, 20, 30, 60, 90, 120)
    private val seekHoldResetMs = 700L

    // --- Retry policy (js/player.js retryOrFailPlayback) - live gets a much bigger budget than VOD/series ---
    private val MAX_PLAYBACK_RETRIES = 2
    private val PLAYBACK_RETRY_DELAY_MS = 2000L
    private val MAX_LIVE_PLAYBACK_RETRIES = 15
    private val LIVE_PLAYBACK_RETRY_DELAY_MS = 4000L

    // --- Live robustness ---
    // Live never gives up on a retry count: it keeps cycling through the
    // channel's sibling qualities (see LiveVariants) for as long as the
    // outage lasts, up to this budget.
    private val LIVE_OUTAGE_BUDGET_MS = 10 * 60_000L
    private val LIVE_CYCLE_BACKOFF_MS = longArrayOf(1_000L, 3_000L, 6_000L, 10_000L)
    private val LIVE_SWITCH_DELAY_MS = 300L
    // Start only once this much stream is buffered: every later drop is
    // absorbed by it while ResilientLiveDataSource reconnects. Set in
    // Réglages > Lecteur (see bindLivePreferences).
    private var liveStartCushionMs = PreferencesStore.DEFAULT_LIVE_BUFFER_SECONDS * 1_000L
    // Beyond the cushion, a panel that doesn't burst still gets this long to fill it.
    private val LIVE_START_EXTRA_WAIT_MS = 4_000L
    // A live item stuck loading this long is dead on that quality.
    private val LIVE_START_TIMEOUT_MS = 15_000L
    private val LIVE_STALL_TIMEOUT_MS = 25_000L
    private val LIVE_VARIANT_LOOKUP_DELAY_MS = 10_000L
    // Guide refresh when no programme end is known (gap in the guide, no guide at all).
    private val LIVE_EPG_RETRY_MS = 5 * 60_000L
    // Drawer rows whose "now" line is fetched around the focused one.
    private val ZAP_EPG_RADIUS = 4

    private var liveVariantLoader: (suspend (selected: ZapItem, categoryId: String) -> List<ZapItem>?)? = null
    private var liveVariants: List<ZapItem> = emptyList()
    private var liveVariantIndex = 0
    private var liveOutageStartedAt = 0L
    private var liveFailuresInOutage = 0
    private var liveHasPlayed = false
    private var liveVariantJob: Job? = null
    private var liveStartJob: Job? = null
    private var liveStallJob: Job? = null

    // --- Rediffusion (catch-up) ---
    // A program is served from a minute, never from a byte offset: the
    // player plays a segment starting replayOffsetMin into the program, and
    // seek/resume/reconnect rebuild the URL further in (see TimeshiftUrl).
    private var replay: TimeshiftUrl? = null
    private var replayOffsetMin = 0
    // The launched channel first, then its same-name qualities that also keep an archive.
    private var replayVariants: List<ZapItem> = emptyList()
    private var replayVariantIndex = 0
    private var replayFormatSwitched = false
    private val replayRecovery = ReplayRecovery()
    // Program position the user is seeking to, shown until the new segment starts.
    private var replaySeekTargetMs: Long? = null
    private var replaySeekJob: Job? = null
    private var replayStartJob: Job? = null
    private var replayPlayingUrl: String? = null
    private var replayResumeAfterBackground: Pair<Long, Boolean>? = null
    // When the last stream connection was dropped: panels limiting
    // connections keep counting it for a moment, so the next one waits.
    private var lastStreamStoppedAtMs = 0L
    private val REPLAY_CONNECTION_GAP_MS = 1_000L
    private val REPLAY_SEEK_SETTLE_MS = 900L
    // Within this much of the program's end, an end of stream is the real end.
    private val REPLAY_END_TOLERANCE_MS = 90_000L

    private var liveQualityChoices: Map<String, LiveQualityChoice> = emptyMap()
    private var rememberLiveQuality: (suspend (channelKey: String, choice: LiveQualityChoice) -> Unit)? = null
    private var livePreferencesJob: Job? = null

    /** The live cushion from Réglages and the per-channel quality memory, kept current all session. */
    fun bindLivePreferences(
        qualityChoices: Flow<Map<String, LiveQualityChoice>>,
        bufferSeconds: Flow<Int>,
        rememberQuality: suspend (channelKey: String, choice: LiveQualityChoice) -> Unit
    ) {
        rememberLiveQuality = rememberQuality
        livePreferencesJob?.cancel()
        livePreferencesJob = viewModelScope.launch {
            launch { qualityChoices.collect { liveQualityChoices = it } }
            bufferSeconds.collect { liveStartCushionMs = it * 1_000L }
        }
    }

    /** Refresh the Activity-owned catalog source after recreation or account change. */
    private var liveEpgLoader: (suspend (channelId: String) -> List<com.btv.data.cache.EpgProgramInfo>?)? = null
    private var liveEpgJob: Job? = null
    private var zapEpgJob: Job? = null

    /** The short-guide source for the OSD and the zap drawer (Activity-owned session). */
    fun setLiveEpgLoader(loader: (suspend (channelId: String) -> List<com.btv.data.cache.EpgProgramInfo>?)?) {
        liveEpgLoader = loader
    }

    fun setLiveVariantLoader(loader: (suspend (selected: ZapItem, categoryId: String) -> List<ZapItem>?)?) {
        liveVariantLoader = loader
    }

    init {
        setupPlayerListener()
    }

    private fun setupPlayerListener() {
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    recordStartedMedia()
                    // A healthy playback restart clears the retry budget and
                    // the pause watchdog - matches Tizen's 'play' handler
                    // (js/player.js: resetPlaybackRetryBudget/disarmPauseWatchdog).
                    retryJob?.cancel()
                    disarmPauseWatchdog()
                    if (isLive()) onLivePlaying()
                    if (isReplay()) onReplayPlaying()
                    _uiState.update { it.copy(retryCount = 0) }
                } else if (activePlayback && !appInBackground && player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                    armPauseWatchdog()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.update {
                    it.copy(
                        playbackState = playbackState,
                        isLoading = playbackState == Player.STATE_BUFFERING,
                        isSeekable = isReplay() || player.isCurrentMediaItemSeekable
                    )
                }
                if (activePlayback && playbackState == Player.STATE_ENDED) {
                    if (isReplay()) onReplayEnded() else handlePlaybackEnded()
                }
                if (activePlayback && isLive()) onLivePlaybackStateChanged(playbackState)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                _uiState.update { it.copy(isSeekable = isReplay() || player.isCurrentMediaItemSeekable) }
            }

            override fun onPositionDiscontinuity(oldPos: Player.PositionInfo, newPos: Player.PositionInfo, reason: Int) {
                _uiState.update { it.copy(currentPosition = replaySegmentStartMs() + newPos.positionMs) }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (activePlayback) handlePlaybackError(error)
            }

            // Keeps the OSD's Audio/Subtitle button labels honest even when
            // nothing was ever manually selected - without this, a track
            // ExoPlayer auto-selects by default (e.g. forced subtitles) shows
            // as generic "Audio"/"Désactivés" until the user opens the menu,
            // even though it's already actually playing.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                reapplyPreferredTracks()
                refreshCurrentTrackLabels()
            }
        })

        startProgressTracking()
    }

    // A manually-picked track was tied to a `TrackSelectionOverride` keyed on
    // the CURRENT media's `TrackGroup` object (js/player.js:1160-1170's
    // `applyPreferredAudioTrack`/`applyPreferredSubtitleTrack` instead key by
    // label, which survives a manifest change). Since the same ExoPlayer
    // instance is reused across episodes/zaps (see playStream), a new
    // MediaItem's track groups are new objects the old override never
    // matches - the pick silently reverted to ExoPlayer's default every time.
    // Remembering the label and re-finding+reapplying it against the NEW
    // groups on every onTracksChanged closes that gap.
    private var preferredAudioLabel: String? = null
    private var preferredSubtitleLabel: String? = null
    private var trackPreferenceJob: Job? = null

    /**
     * Tizen playStream (js/player.js getTrackPref): a saved choice wins over
     * the label carried from the previous episode. For an episode, the
     * choice saved for the episode itself and the one saved for its whole
     * series are both read, and the most recent wins: switching to French
     * once keeps every later episode in French, across restarts too. A
     * language missing from the new stream is simply not applied.
     */
    private fun loadTrackPreference(id: String?, type: String, seriesId: String? = null) {
        trackPreferenceJob?.cancel()
        val repository = trackPreferenceRepository ?: return
        id ?: return
        trackPreferenceJob = viewModelScope.launch {
            val saved = try {
                listOfNotNull(
                    repository.get(type, id),
                    seriesId?.let { repository.get(type, seriesTrackKey(it)) }
                ).sortedByDescending { it.updatedAt }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("BtvPlayer", "Track preference unavailable: ${error.javaClass.simpleName}")
                emptyList()
            }
            if (saved.isEmpty()) return@launch
            if (contentId != id || progressType != type) return@launch
            saved.firstNotNullOfOrNull { it.audioLabel }?.let { preferredAudioLabel = it }
            saved.firstNotNullOfOrNull { it.subtitleLabel }?.let { preferredSubtitleLabel = it }
            reapplyPreferredTracks()
            refreshCurrentTrackLabels()
        }
    }

    /** Tizen confirmTrackMenuSelection -> saveTrackPref: persisted right away. */
    private fun saveTrackPreference() {
        val repository = trackPreferenceRepository ?: return
        val id = contentId ?: return
        val accountKey = repository.currentAccountKey() ?: return
        val type = progressType
        val audio = preferredAudioLabel
        val subtitle = preferredSubtitleLabel
        val seriesId = _uiState.value.seriesId
        viewModelScope.launch {
            try {
                repository.save(accountKey, type, id, audio, subtitle)
                // The series' choice too, for its other episodes.
                if (seriesId != null) repository.save(accountKey, type, seriesTrackKey(seriesId), audio, subtitle)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("BtvPlayer", "Track preference not saved: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun reapplyPreferredTracks() {
        preferredAudioLabel?.let { label ->
            val option = preferredOption(buildTrackOptions(TrackMenuType.AUDIO), label)
            if (option != null && !option.isSelected) applyTrackOverride(TrackMenuType.AUDIO, option)
        }
        preferredSubtitleLabel?.let { label ->
            val option = preferredOption(buildTrackOptions(TrackMenuType.SUBTITLE), label)
            if (option != null && !option.isSelected) applyTrackOverride(TrackMenuType.SUBTITLE, option)
        }
    }

    /** The same label, else the same language under another label ("Français" / "FRE"). */
    private fun preferredOption(options: List<TrackOption>, label: String): TrackOption? =
        options.firstOrNull { it.label == label }
            ?: options.firstOrNull { it.isSelected && labelMatchesLanguage(label, it.language) }
            ?: options.firstOrNull { labelMatchesLanguage(label, it.language) }

    /** Track preference row shared by every episode of a series. */
    private fun seriesTrackKey(seriesId: String) = "series:$seriesId"

    private fun refreshCurrentTrackLabels() {
        val audioSelected = buildTrackOptions(TrackMenuType.AUDIO).firstOrNull { it.isSelected }
        val subtitleSelected = buildTrackOptions(TrackMenuType.SUBTITLE).firstOrNull { it.isSelected }
        _uiState.update {
            it.copy(
                currentAudioLabel = audioSelected?.label ?: it.currentAudioLabel,
                currentSubtitleLabel = subtitleSelected?.label ?: "Désactivés"
            )
        }
    }

    // -------------------------------------------------------------------
    // Loading / resume / retry
    // -------------------------------------------------------------------

    fun loadStream(streamUrl: String, contentType: String = "video/mp4", resumePositionMs: Long = 0L) {
        retryJob?.cancel()
        resetLiveRecovery()
        activePlayback = true
        _uiState.update {
            it.copy(streamUrl = streamUrl, contentType = contentType, errorMessage = null, retryCount = 0, isSeekable = false)
        }
        playStream(streamUrl, resumePositionMs)
    }

    /**
     * Entry point from PlayerScreen: sets the content identity (for
     * progress persistence + the zap list's "currently playing" marker),
     * then checks for resumable progress before starting playback - same
     * as Tizen's playItemWithResume, which never resumes silently.
     */
    fun loadStreamWithResumeCheck(
        streamUrl: String,
        contentType: String = "video/mp4",
        contentId: String?,
        progressType: String,
        contentName: String,
        zapList: List<ZapItem> = emptyList(),
        seriesId: String? = null,
        seriesName: String? = null,
        seasonNum: Int? = null,
        posterUrl: String? = null,
        categoryId: String = "",
        categoryName: String = "",
        liveVariants: List<ZapItem> = emptyList(),
        applyRememberedQuality: Boolean = true
    ) {
        // A live channel restarts on the quality last picked for its family
        // (Browse, OSD), unless this launch is itself an explicit pick.
        val remembered = if (progressType == "LIVE" && contentId != null && applyRememberedQuality) {
            liveQualityChoices[liveChannelKey(contentName)]?.takeIf { it.streamId != contentId }
        } else null
        val rememberedUrl = remembered?.let { liveUrlForStreamId(streamUrl, it.streamId) }
        if (remembered == null || rememberedUrl == null) {
            startContent(
                streamUrl, contentType, contentId, progressType, contentName, zapList, seriesId, seriesName,
                seasonNum, posterUrl, categoryId, categoryName, liveVariants
            )
            return
        }
        val requested = ZapItem(contentId.orEmpty(), contentName, posterUrl, streamUrl)
        startContent(
            rememberedUrl, contentType, remembered.streamId, progressType, remembered.name, zapList, seriesId,
            seriesName, seasonNum, posterUrl, categoryId, categoryName, liveVariants + requested
        )
    }

    private fun startContent(
        streamUrl: String,
        contentType: String = "video/mp4",
        contentId: String?,
        progressType: String,
        contentName: String,
        zapList: List<ZapItem> = emptyList(),
        seriesId: String? = null,
        seriesName: String? = null,
        seasonNum: Int? = null,
        posterUrl: String? = null,
        categoryId: String = "",
        categoryName: String = "",
        liveVariants: List<ZapItem> = emptyList()
    ) {
        val outgoingSave = persistProgressSnapshot(progressSnapshot())
        val generation = invalidatePlayback()
        markStreamStopped()
        player?.stop()
        player?.clearMediaItems()
        this.contentId = contentId
        this.progressType = progressType
        prepareReplay(streamUrl, liveVariants)
        loadTrackPreference(contentId, progressType, seriesId)
        historyPosterUrl = posterUrl ?: zapList.firstOrNull { it.id == contentId }?.posterUrl
        historyCategoryId = categoryId.ifEmpty { seriesId.orEmpty() }
        historyCategoryName = categoryName.ifEmpty { seriesName.orEmpty() }
        prepareLiveVariants(contentId, contentName, streamUrl, zapList + liveVariants, categoryId, generation)
        lastProgressSaveAt = 0L
        _resumePrompt.value = null
        _nextSeasonPrompt.value = null
        _uiState.update {
            it.copy(
                streamUrl = streamUrl,
                contentType = contentType,
                contentName = contentName,
                zapList = zapList,
                zapIndex = zapList.indexOfFirst { z -> z.id == contentId },
                seriesId = seriesId,
                seriesName = seriesName,
                seasonNum = seasonNum,
                currentPosition = 0L,
                duration = C.TIME_UNSET,
                bufferedPosition = 0L,
                isPlaying = false,
                isSeekable = false,
                isLive = progressType == "LIVE",
                isReplay = progressType == "REPLAY",
                isLoading = true,
                errorMessage = null,
                retryCount = 0,
                isReconnecting = false,
                isPrebuffering = false,
                liveFallbackName = null
            )
        }
        // A series episode opened from a mixed list ("Continuer à regarder"/
        // "Consulté récemment") arrives with THAT list as zapList, not the
        // season's episodes - rebuild it from the authoritative season data
        // (js/browse.js buildSeasonZapList), so "Suivant"/auto-advance never
        // jumps into an unrelated show mixed into that list.
        val id = contentId
        val useCase = getPlaybackProgressUseCase
        resumeCheckJob = viewModelScope.launch {
            outgoingSave?.join()
            if (generation != playbackGeneration) return@launch
            if (seriesId != null && seasonNum != null) rebuildZapListForSeries(seriesId, seasonNum, id)
            val resumePositionMs = try {
                if (id != null && useCase != null && useCase.shouldResumePlayback(id, progressType)) {
                    useCase.getResumePosition(id, progressType)
                } else 0L
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                0L // A broken progress entry must not strand the player on its loading screen.
            }
            if (generation != playbackGeneration) return@launch
            if (resumePositionMs > 0L) {
                _uiState.update { it.copy(isLoading = false) }
                _resumePrompt.value = ResumePrompt(resumePositionMs)
            } else loadStream(streamUrl, contentType)
        }
    }

    /** User's answer to the resume prompt - true resumes at the saved position, false starts over. */
    fun confirmResume(resume: Boolean) {
        val prompt = _resumePrompt.value
        _resumePrompt.value = null
        val state = _uiState.value
        loadStream(state.streamUrl, state.contentType, if (resume) prompt?.resumePositionMs ?: 0L else 0L)
    }

    /**
     * Back on the resume dialog (js/modals.js handleResumeDialogKey: keyCode
     * 10009/8 -> "Retour : annule completement, ne lance pas la lecture") -
     * cancels outright, never starts playback, and leaves the player.
     */
    fun cancelResume() {
        _resumePrompt.value = null
        _exitRequested.value = true
    }

    private fun isLive(): Boolean = progressType == "LIVE"
    private fun isReplay(): Boolean = progressType == "REPLAY" && replay != null

    /**
     * Live gets a far more generous retry budget than VOD/series (js/player.js
     * retryOrFailPlayback): a server/network blip on a live channel can
     * outlast the ~4s covered by 2 quick VOD retries, and giving up too soon
     * forces the user to back out and re-tune the channel by hand. A VOD/
     * episode that's genuinely unavailable, on the other hand, should still
     * fail fast - there's nothing to gain from insisting.
     */
    private fun maxRetriesFor(): Int = when {
        isReplay() -> 0 // bounded by ReplayRecovery's outage time, not a count
        isLive() -> MAX_LIVE_PLAYBACK_RETRIES
        else -> MAX_PLAYBACK_RETRIES
    }
    private fun retryDelayMsFor(): Long = if (isLive()) LIVE_PLAYBACK_RETRY_DELAY_MS else PLAYBACK_RETRY_DELAY_MS

    /** [resumePositionMs] is a position in the program, for a replay as for any VOD. */
    private fun playStream(streamUrl: String, resumePositionMs: Long = 0L, playWhenReady: Boolean = true) {
        if (isReplay()) {
            playReplayAt(resumePositionMs, playWhenReady)
            return
        }
        startMedia(streamUrl, resumePositionMs, playWhenReady)
    }

    private fun startMedia(streamUrl: String, resumePositionMs: Long, playWhenReady: Boolean) {
        try {
            val mediaItem = MediaItem.Builder()
                .setUri(streamUrl)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(_uiState.value.contentName.ifEmpty { "bTV" })
                    .build())
                .build()
            player?.setMediaItem(mediaItem)
            if (resumePositionMs > 0) player?.seekTo(resumePositionMs)
            player?.prepare()
            val holdForCushion = playWhenReady && isLive()
            player?.playWhenReady = playWhenReady && !holdForCushion
            _uiState.update { it.copy(errorMessage = null, maxRetries = maxRetriesFor()) }
            if (isLive()) {
                armLiveStallWatchdog(LIVE_START_TIMEOUT_MS)
                if (holdForCushion) startLiveCushion()
            }
        } catch (e: Exception) {
            handlePlaybackError(e.message ?: "Erreur de lecture")
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        if (isReplay()) {
            if (activePlayback && !appInBackground) handleReplayFailure(error.message ?: "Erreur de lecture", error.httpResponseCode())
            return
        }
        handlePlaybackError(error.message ?: "Erreur de lecture")
    }

    private fun handlePlaybackError(errorMessage: String) {
        val currentState = _uiState.value
        if (!activePlayback || appInBackground || currentState.streamUrl.isEmpty()) return
        if (isLive()) {
            handleLiveFailure(errorMessage)
            return
        }
        val maxRetries = maxRetriesFor()
        // Live reconnects at the live edge (no explicit seek); VOD/series resumes exactly where it dropped.
        val resumeAt = if (isLive()) 0L else playbackPositionMs()
        if (currentState.retryCount < maxRetries) {
            retryJob?.cancel()
            val generation = playbackGeneration
            retryJob = viewModelScope.launch {
                delay(retryDelayMsFor())
                if (generation != playbackGeneration || !activePlayback || _uiState.value.streamUrl != currentState.streamUrl) return@launch
                _uiState.update { it.copy(retryCount = it.retryCount + 1, maxRetries = maxRetries) }
                playStream(currentState.streamUrl, resumeAt)
            }
        } else {
            _uiState.update { it.copy(errorMessage = errorMessage, isPlaying = false, maxRetries = maxRetries) }
        }
    }

    // -------------------------------------------------------------------
    // Live robustness. Short drops never reach this code: they are absorbed
    // by the start cushion while ResilientLiveDataSource reopens the
    // connection. What does reach it - a quality that won't start, stays
    // stalled, or can't be reopened - moves on to the channel's next sibling
    // quality, cycling for as long as the outage lasts (bounded by
    // LIVE_OUTAGE_BUDGET_MS) instead of giving up after a retry count.
    // -------------------------------------------------------------------

    private var liveCushionToken = 0

    private fun prepareLiveVariants(
        contentId: String?,
        name: String,
        streamUrl: String,
        zapList: List<ZapItem>,
        categoryId: String,
        generation: Long
    ) {
        liveVariantJob?.cancel()
        liveHasPlayed = false
        liveVariantIndex = 0
        if (progressType != "LIVE" || contentId == null) {
            liveVariants = emptyList()
            publishLiveQualities()
            liveEpgJob?.cancel()
            _uiState.update { it.copy(liveNowPlaying = null) }
            return
        }
        val selected = ZapItem(contentId, name, null, streamUrl)
        liveVariants = listOf(selected)
        publishLiveQualities()
        startLiveEpg(generation)
        val loader = liveVariantLoader
        // "Tout afficher" can hand over thousands of channels: match off the main thread.
        liveVariantJob = viewModelScope.launch {
            val fromList = withContext(Dispatchers.Default) { buildLiveFallbackChain(selected, zapList) }
            if (generation != playbackGeneration) return@launch
            adoptLiveVariants(fromList)
            // The catalog-wide lookup may download the whole channel list:
            // keep it off the bandwidth the start cushion is filling.
            delay(LIVE_VARIANT_LOOKUP_DELAY_MS)
            val categoryChannels = try {
                loader?.invoke(selected, categoryId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@launch
            val full = withContext(Dispatchers.Default) { buildLiveFallbackChain(selected, zapList + categoryChannels) }
            if (generation != playbackGeneration) return@launch
            adoptLiveVariants(full)
        }
    }

    /**
     * Keeps the OSD's "now / next" current for the channel playing: right
     * away from the cache, then again when the programme ends (or every few
     * minutes if the guide has a gap). Providers often fill the guide on one
     * quality only, so the siblings are asked too.
     */
    private fun startLiveEpg(generation: Long) {
        liveEpgJob?.cancel()
        _uiState.update { it.copy(liveNowPlaying = null) }
        val loader = liveEpgLoader ?: return
        liveEpgJob = viewModelScope.launch {
            while (generation == playbackGeneration) {
                val now = System.currentTimeMillis()
                val candidates = (listOfNotNull(contentId) + liveVariants.map { it.id }).distinct()
                var program: LiveProgram? = null
                for (id in candidates) {
                    val listings = com.btv.util.guarded(LIVE_TAG, "Live guide") { loader(id) } ?: continue
                    program = nowAndNext(listings, now)
                    if (program != null) break
                }
                if (generation != playbackGeneration) return@launch
                _uiState.update { it.copy(liveNowPlaying = program) }
                val untilNext = program?.endMs?.minus(System.currentTimeMillis())
                delay((untilNext ?: LIVE_EPG_RETRY_MS).coerceIn(5_000L, LIVE_EPG_RETRY_MS) + 1_000L)
            }
        }
    }

    /** Fills the drawer's "now" line for the rows around the focused one, nearest first. */
    private fun requestZapEpg() {
        if (!isLive()) return
        val loader = liveEpgLoader ?: return
        val state = _uiState.value
        val focus = state.episodeFocusIndex
        val now = System.currentTimeMillis()
        val ids = (0..ZAP_EPG_RADIUS).flatMap { d -> listOf(focus + d, focus - d) }
            .distinct()
            .mapNotNull { state.zapList.getOrNull(it)?.id }
            .filter { id -> state.zapPrograms[id]?.let { it.endMs <= now } != false }
        if (ids.isEmpty()) return
        zapEpgJob?.cancel()
        zapEpgJob = viewModelScope.launch {
            for (id in ids) {
                val listings = com.btv.util.guarded(LIVE_TAG, "Zap guide") { loader(id) } ?: continue
                val program = nowAndNext(listings, System.currentTimeMillis()) ?: continue
                _uiState.update { it.copy(zapPrograms = it.zapPrograms + (id to program)) }
            }
        }
    }

    /** Swaps in a richer chain without losing track of the quality currently playing. */
    private fun adoptLiveVariants(chain: List<ZapItem>) {
        val currentId = liveVariants.getOrNull(liveVariantIndex)?.id
        liveVariants = chain
        liveVariantIndex = chain.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        publishLiveQualities()
        if (chain.size > 1) {
            android.util.Log.i(LIVE_TAG, "Fallback chain: ${chain.joinToString(" > ") { it.name }}")
        }
    }

    /** OSD "Qualité" menu: every sibling, best first, the one playing ticked. */
    private fun publishLiveQualities() {
        val variants = liveVariants
        if (variants.size < 2) {
            _uiState.update { it.copy(liveQualities = emptyList()) }
            return
        }
        val currentId = variants.getOrNull(liveVariantIndex)?.id
        val sorted = sortByQualityDescending(variants) { it.name }
        val labels = liveQualityLabels(sorted) { it.name }
        _uiState.update { state ->
            state.copy(liveQualities = sorted.mapIndexed { index, variant ->
                TrackOption(variant.id, labels[index], variant.id == currentId)
            })
        }
    }

    /** A quality picked by hand becomes the new first choice, remembered for next time. */
    private fun switchLiveQuality(id: String) {
        val variant = liveVariants.firstOrNull { it.id == id } ?: return
        val url = variant.streamUrl ?: return
        if (liveVariants.getOrNull(liveVariantIndex)?.id == id) return
        val label = _uiState.value.liveQualities.firstOrNull { it.id == id }?.label ?: variant.name
        liveVariants = buildLiveFallbackChain(variant, liveVariants)
        liveVariantIndex = 0
        liveHasPlayed = false
        rememberQuality(variant)
        publishLiveQualities()
        _uiState.update { it.copy(contentName = variant.name, liveFallbackName = null) }
        flash("Qualité : $label")
        loadStream(url, _uiState.value.contentType)
    }

    private fun rememberQuality(variant: ZapItem) {
        val key = liveChannelKey(variant.name)
        if (key.isEmpty()) return
        val choice = LiveQualityChoice(variant.id, variant.name)
        liveQualityChoices = liveQualityChoices + (key to choice)
        val remember = rememberLiveQuality ?: return
        viewModelScope.launch {
            try {
                remember(key, choice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w(LIVE_TAG, "Quality choice not saved: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun resetLiveRecovery() {
        liveOutageStartedAt = 0L
        liveFailuresInOutage = 0
        liveStallJob?.cancel()
        liveStartJob?.cancel()
        liveCushionToken++
        _uiState.update { it.copy(isReconnecting = false, isPrebuffering = false) }
    }

    private fun onLivePlaying() {
        liveHasPlayed = true
        liveStallJob?.cancel()
        if (liveOutageStartedAt != 0L) {
            val outageMs = android.os.SystemClock.elapsedRealtime() - liveOutageStartedAt
            android.util.Log.i(LIVE_TAG, "Live recovered after ${outageMs}ms on ${liveVariants.getOrNull(liveVariantIndex)?.name}")
        }
        liveOutageStartedAt = 0L
        liveFailuresInOutage = 0
        _uiState.update { it.copy(isReconnecting = false) }
    }

    private fun onLivePlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            // Before the first frame, playStream's start watchdog is already running.
            Player.STATE_BUFFERING -> if (liveHasPlayed) {
                _uiState.update { it.copy(isReconnecting = true) }
                armLiveStallWatchdog(LIVE_STALL_TIMEOUT_MS)
            }
            Player.STATE_READY -> {
                liveStallJob?.cancel()
                _uiState.update { it.copy(isReconnecting = false) }
            }
            // A live channel has no end: the panel cut it.
            Player.STATE_ENDED -> handleLiveFailure("Flux interrompu par le serveur")
        }
    }

    private fun armLiveStallWatchdog(timeoutMs: Long) {
        liveStallJob?.cancel()
        val generation = playbackGeneration
        val url = _uiState.value.streamUrl
        liveStallJob = viewModelScope.launch {
            delay(timeoutMs)
            if (generation != playbackGeneration || !activePlayback || appInBackground) return@launch
            if (retryJob?.isActive == true || _uiState.value.streamUrl != url) return@launch
            if (player?.playbackState == Player.STATE_READY) return@launch
            android.util.Log.w(LIVE_TAG, "Live stalled for ${timeoutMs}ms")
            handleLiveFailure("Flux bloqué")
        }
    }

    /**
     * Holds the first frame until liveStartCushionMs of stream is
     * buffered (panels usually burst that much at once, so it rarely shows).
     * A live feed then arrives in real time, so that margin stays for the
     * whole session and covers the reconnections done underneath.
     */
    private fun startLiveCushion() {
        liveStartJob?.cancel()
        val token = ++liveCushionToken
        val generation = playbackGeneration
        _uiState.update { it.copy(isPrebuffering = true) }
        liveStartJob = viewModelScope.launch {
            try {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val cushionMs = liveStartCushionMs
                while (android.os.SystemClock.elapsedRealtime() - startedAt < cushionMs + LIVE_START_EXTRA_WAIT_MS) {
                    val current = player ?: return@launch
                    if (current.playWhenReady) return@launch // the user already pressed play
                    val buffered = current.bufferedPosition - current.currentPosition
                    if (current.playbackState == Player.STATE_READY && buffered >= cushionMs) break
                    delay(200)
                }
                if (generation == playbackGeneration && activePlayback && !appInBackground) player?.playWhenReady = true
            } finally {
                if (token == liveCushionToken) _uiState.update { it.copy(isPrebuffering = false) }
            }
        }
    }

    private fun handleLiveFailure(errorMessage: String) {
        liveStallJob?.cancel()
        liveStartJob?.cancel()
        liveCushionToken++
        val now = android.os.SystemClock.elapsedRealtime()
        if (liveOutageStartedAt == 0L) liveOutageStartedAt = now
        if (now - liveOutageStartedAt > LIVE_OUTAGE_BUDGET_MS) {
            retryJob?.cancel()
            android.util.Log.w(LIVE_TAG, "Live outage budget exhausted: $errorMessage")
            _uiState.update {
                it.copy(
                    errorMessage = "Le direct ne répond plus depuis 10 minutes ($errorMessage)",
                    isPlaying = false, isLoading = false, isReconnecting = false, isPrebuffering = false,
                    retryCount = 0, maxRetries = 0
                )
            }
            return
        }
        liveFailuresInOutage++
        val state = _uiState.value
        val chain = liveVariants.ifEmpty { listOf(ZapItem(contentId.orEmpty(), state.contentName, null, state.streamUrl)) }
        val nextIndex = (liveVariantIndex + 1) % chain.size
        val next = chain[nextIndex]
        val url = next.streamUrl ?: return
        // Every quality failed once already: the line itself is down, back off.
        val cycle = (liveFailuresInOutage - 1) / chain.size
        val delayMs = if (cycle == 0) LIVE_SWITCH_DELAY_MS
            else LIVE_CYCLE_BACKOFF_MS[(cycle - 1).coerceAtMost(LIVE_CYCLE_BACKOFF_MS.size - 1)]
        android.util.Log.w(LIVE_TAG, "Live failure #$liveFailuresInOutage ($errorMessage), next: ${next.name} in ${delayMs}ms")
        val generation = playbackGeneration
        retryJob?.cancel()
        _uiState.update {
            it.copy(isReconnecting = true, isLoading = true, isPrebuffering = false, retryCount = liveFailuresInOutage, maxRetries = 0)
        }
        retryJob = viewModelScope.launch {
            delay(delayMs)
            if (generation != playbackGeneration || !activePlayback || appInBackground) return@launch
            liveVariantIndex = nextIndex
            publishLiveQualities()
            if (url != _uiState.value.streamUrl) flash(if (nextIndex == 0) "Retour sur ${next.name}" else "Bascule sur ${next.name}")
            _uiState.update { it.copy(streamUrl = url, liveFallbackName = next.name.takeIf { nextIndex != 0 }) }
            playStream(url)
        }
    }

    // -------------------------------------------------------------------
    // Rediffusion. A program is one archive segment the panel serves from a
    // given minute: seeking past what's loaded, resuming, and recovering
    // from a drop all reopen it further in (TimeshiftUrl), one connection
    // at a time, and failures are waited out instead of failing in 4 s
    // like a VOD (ReplayRecovery).
    // -------------------------------------------------------------------

    private fun prepareReplay(streamUrl: String, variants: List<ZapItem>) {
        replayFormatSwitched = false
        replayRecovery.reset()
        replayOffsetMin = 0
        replayPlayingUrl = null
        val parsed = if (progressType == "REPLAY") TimeshiftUrl.parse(streamUrl) else null
        replay = parsed?.let { url -> ReplayFormatMemory.get(url.accountKey)?.let { url.copy(format = it) } ?: url }
        if (parsed == null) {
            replayVariants = emptyList()
            return
        }
        // Zapping to another program of the channel keeps its qualities.
        if (variants.isNotEmpty() || replayVariants.none { it.id == parsed.streamId }) {
            replayVariants = variants.ifEmpty { listOf(ZapItem(parsed.streamId, "", null, null)) }
        }
        replayVariantIndex = replayVariants.indexOfFirst { it.id == parsed.streamId }.coerceAtLeast(0)
    }

    private fun replaySegmentStartMs(): Long = if (isReplay()) replayOffsetMin * 60_000L else 0L

    /** Where playback is in the program (a replay segment starts some minutes in). */
    private fun playbackPositionMs(): Long =
        replaySegmentStartMs() + (player?.currentPosition ?: 0L).coerceAtLeast(0L)

    private fun markStreamStopped() {
        if (player?.currentMediaItem != null && player.playbackState != Player.STATE_IDLE) {
            lastStreamStoppedAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    /** Opens the program at [positionMs], rounded to the minute the panel can serve. */
    private fun playReplayAt(positionMs: Long, playWhenReady: Boolean = true) {
        val program = replay ?: return
        replaySeekJob?.cancel()
        replayStartJob?.cancel()
        val lastMinute = (program.durationMinutes - 1).coerceAtLeast(0)
        val offset = ((positionMs.coerceAtLeast(0L) + 30_000L) / 60_000L).toInt().coerceAtMost(lastMinute)
        val streamId = replayVariants.getOrNull(replayVariantIndex)?.id ?: program.streamId
        val url = program.build(offset, streamId)
        replaySeekTargetMs = offset * 60_000L
        // The connection being replaced still counts on the panel's side for a moment.
        markStreamStopped()
        player?.stop()
        val waitMs = REPLAY_CONNECTION_GAP_MS - (android.os.SystemClock.elapsedRealtime() - lastStreamStoppedAtMs)
        val generation = playbackGeneration
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        replayStartJob = viewModelScope.launch {
            if (waitMs > 0) delay(waitMs)
            if (generation != playbackGeneration || !activePlayback || appInBackground) return@launch
            replayOffsetMin = offset
            replayPlayingUrl = url
            replaySeekTargetMs = null
            startMedia(url, 0L, playWhenReady)
        }
    }

    /**
     * Within the loaded segment, a seekable stream seeks natively; anything
     * else waits for the key presses to settle and reopens the program at
     * the target minute - one new connection per seek, not one per press.
     */
    private fun seekReplayBy(deltaMs: Long) {
        val program = replay ?: return
        val currentPlayer = player ?: return
        val from = replaySeekTargetMs ?: playbackPositionMs()
        val target = (from + deltaMs).coerceIn(0L, (program.durationMs - 5_000L).coerceAtLeast(0L))
        val segmentStart = replaySegmentStartMs()
        val segmentDuration = currentPlayer.duration
        val pendingSeek = replaySeekJob?.isActive == true || replayStartJob?.isActive == true
        if (!pendingSeek && currentPlayer.isCurrentMediaItemSeekable && segmentDuration != C.TIME_UNSET &&
            target >= segmentStart && target - segmentStart < segmentDuration) {
            currentPlayer.seekTo(target - segmentStart)
            return
        }
        replaySeekTargetMs = target
        _uiState.update { it.copy(currentPosition = target) }
        replaySeekJob?.cancel()
        replayStartJob?.cancel()
        val generation = playbackGeneration
        replaySeekJob = viewModelScope.launch {
            delay(REPLAY_SEEK_SETTLE_MS)
            if (generation != playbackGeneration || !activePlayback) return@launch
            val playing = player?.playWhenReady ?: true
            playReplayAt(target, playing)
        }
    }

    private fun onReplayPlaying() {
        replay?.let { ReplayFormatMemory.put(it.accountKey, it.format) }
        replayRecovery.reset()
        _uiState.update { it.copy(isReconnecting = false) }
    }

    /** The panel ending the segment well before the program does is a drop, not the end. */
    private fun onReplayEnded() {
        val program = replay ?: return
        if (playbackPositionMs() < program.durationMs - REPLAY_END_TOLERANCE_MS) {
            handleReplayFailure("Flux interrompu par le serveur", null)
        } else {
            handlePlaybackEnded()
        }
    }

    private fun handleReplayFailure(errorMessage: String, httpCode: Int?) {
        val program = replay ?: return
        val resumeAt = replaySeekTargetMs ?: playbackPositionMs()
        val canSwitchFormat = !replayFormatSwitched && ReplayFormatMemory.get(program.accountKey) == null
        val decision = replayRecovery.onFailure(
            android.os.SystemClock.elapsedRealtime(), httpCode, replayVariants.size, canSwitchFormat
        )
        android.util.Log.w(REPLAY_TAG, "Replay failure #${replayRecovery.failures} (HTTP $httpCode, $errorMessage): $decision")
        retryJob?.cancel()
        if (decision.giveUp) {
            _uiState.update {
                it.copy(
                    errorMessage = "La rediffusion ne répond pas ($errorMessage)",
                    isPlaying = false, isLoading = false, isReconnecting = false,
                    retryCount = 0, maxRetries = 0
                )
            }
            return
        }
        if (decision.switchFormat) {
            replayFormatSwitched = true
            replay = program.copy(format = if (program.format == TimeshiftFormat.PATH) TimeshiftFormat.PHP else TimeshiftFormat.PATH)
        }
        val variant = if (decision.nextVariant) {
            replayVariantIndex = (replayVariantIndex + 1) % replayVariants.size
            replayVariants[replayVariantIndex]
        } else null
        // The failed socket may still count against the panel's limit.
        lastStreamStoppedAtMs = android.os.SystemClock.elapsedRealtime()
        replaySeekTargetMs = resumeAt
        _uiState.update {
            it.copy(isReconnecting = true, isLoading = true, retryCount = replayRecovery.failures, maxRetries = 0)
        }
        val generation = playbackGeneration
        retryJob = viewModelScope.launch {
            delay(decision.delayMs)
            if (generation != playbackGeneration || !activePlayback || appInBackground) return@launch
            variant?.name?.takeIf { it.isNotEmpty() }?.let { flash("Archive : $it") }
            playReplayAt(resumeAt, playWhenReady = true)
        }
    }

    // -------------------------------------------------------------------
    // Pause watchdog (js/player.js armPauseWatchdog/refreshStalePausedStream):
    // a long enough pause lets the panel's session/segments expire, so
    // resuming later throws instead of just continuing - silently
    // reconnecting the SAME stream (still paused, not resumed) after a long
    // enough pause keeps a later "play" working normally.
    // -------------------------------------------------------------------

    private var pauseWatchdogJob: Job? = null
    private val pauseWatchdogDelayMs = 90_000L

    private fun armPauseWatchdog() {
        if (appInBackground) return
        pauseWatchdogJob?.cancel()
        val generation = playbackGeneration
        pauseWatchdogJob = viewModelScope.launch {
            delay(pauseWatchdogDelayMs)
            if (generation == playbackGeneration && activePlayback && !appInBackground && player?.playbackState == Player.STATE_READY && !player.playWhenReady) {
                refreshStalePausedStream()
            }
        }
    }

    private fun disarmPauseWatchdog() {
        pauseWatchdogJob?.cancel()
    }

    private fun refreshStalePausedStream() {
        val s = _uiState.value
        if (s.streamUrl.isEmpty()) return
        val resumeAt = if (isLive()) 0L else playbackPositionMs()
        flash("Flux réactualisé après une pause prolongée")
        playStream(s.streamUrl, resumeAt, playWhenReady = false)
    }

    // -------------------------------------------------------------------
    // OSD state machine - port of js/input.js's player-view key handling.
    // Each function mirrors handleUp/handleDown/handleLeft/handleRight/
    // handleEnter for currentView === 'player'.
    // -------------------------------------------------------------------

    fun onDirectionUp() {
        val s = _uiState.value
        when {
            s.showExitDialog -> toggleExitDialogFocus()
            s.trackMenuType != null -> moveTrackMenuFocus(-1)
            !s.osdVisible -> showOsd()
            s.osdZone == OsdZone.BUTTONS -> { _uiState.update { it.copy(osdZone = OsdZone.SEEK) }; resetHideTimer() }
            s.osdZone == OsdZone.EPISODES -> {
                if (s.episodeFocusIndex > 0) moveEpisodeFocus(-1) else closeEpisodeList()
            }
            else -> resetHideTimer() // SEEK: no-op, still counts as activity
        }
    }

    fun onDirectionDown() {
        val s = _uiState.value
        when {
            s.showExitDialog -> toggleExitDialogFocus()
            s.trackMenuType != null -> moveTrackMenuFocus(1)
            !s.osdVisible -> showOsd()
            s.osdZone == OsdZone.BUTTONS -> openEpisodeList()
            s.osdZone == OsdZone.SEEK -> { _uiState.update { it.copy(osdZone = OsdZone.BUTTONS) }; resetHideTimer() }
            s.osdZone == OsdZone.EPISODES -> moveEpisodeFocus(1)
        }
    }

    fun onDirectionLeft() = onDirectionHorizontal(-1)
    fun onDirectionRight() = onDirectionHorizontal(1)

    private fun onDirectionHorizontal(sign: Int) {
        val s = _uiState.value
        when {
            s.showExitDialog -> toggleExitDialogFocus()
            s.trackMenuType != null -> Unit // vertical-only list, no-op
            !s.osdVisible -> {
                _uiState.update { it.copy(osdZone = OsdZone.SEEK) }
                showOsd()
                seekHeld(sign)
            }
            s.osdZone == OsdZone.SEEK -> { seekHeld(sign); resetHideTimer() }
            s.osdZone == OsdZone.BUTTONS -> { moveButtonFocus(sign); resetHideTimer() }
            s.osdZone == OsdZone.EPISODES -> Unit // vertical-only list, no-op
        }
    }

    fun onCenter() {
        val s = _uiState.value
        when {
            s.showExitDialog -> confirmExitDialog()
            s.trackMenuType != null -> confirmTrackMenuSelection()
            !s.osdVisible -> { showOsd(); togglePlayPause() }
            s.osdZone == OsdZone.SEEK -> { togglePlayPause(); resetHideTimer() }
            s.osdZone == OsdZone.BUTTONS -> {
                s.playerButtons.getOrNull(s.focusedButtonIndex)?.let(::activateButton)
                // A menu it just opened keeps the OSD up: re-arming the
                // auto-hide here used to hide it underneath the menu and
                // send focus back to Play/Pause.
                if (_uiState.value.trackMenuType == null) resetHideTimer()
            }
            s.osdZone == OsdZone.EPISODES -> selectEpisodeListItem()
        }
    }

    /**
     * Back button while in the player - always consumed internally, one
     * layer at a time (track menu -> episode drawer -> OSD -> exit
     * dialog), matching js/input.js handleBack exactly. Never exits by
     * itself - only the exit dialog's own Center handling (intercepted by
     * PlayerScreen, see confirmExitDialogWith) actually leaves the player.
     */
    fun onBackPressed() {
        val s = _uiState.value
        when {
            s.trackMenuType != null -> { closeTrackMenu(); showOsd() }
            s.showExitDialog -> closeExitDialog()
            s.osdZone == OsdZone.EPISODES -> closeEpisodeList()
            s.osdVisible -> hideOsd()
            else -> openExitDialog()
        }
    }

    // --- Touch (tablet / phone): the remote's actions, aimed directly ---

    /** A tap on the picture: closes what is open, else shows / hides the OSD. */
    fun onScreenTapped() {
        val s = _uiState.value
        when {
            s.showExitDialog -> closeExitDialog()
            s.trackMenuType != null -> { closeTrackMenu(); showOsd() }
            s.osdZone == OsdZone.EPISODES -> closeEpisodeList()
            s.osdVisible -> hideOsd()
            else -> showOsd()
        }
    }

    fun onButtonTapped(index: Int) {
        if (!_uiState.value.osdVisible) showOsd()
        _uiState.update { it.copy(osdZone = OsdZone.BUTTONS, focusedButtonIndex = index) }
        onCenter()
    }

    fun onTrackOptionTapped(index: Int) {
        _uiState.update { it.copy(trackMenuFocusIndex = index) }
        confirmTrackMenuSelection()
    }

    fun onEpisodeTapped(index: Int) {
        _uiState.update { it.copy(osdZone = OsdZone.EPISODES, episodeFocusIndex = index) }
        selectEpisodeListItem()
    }

    fun onExitChoiceTapped(index: Int) {
        _uiState.update { it.copy(exitDialogFocusIndex = index) }
        confirmExitDialog()
    }

    /** Tap or drag on the progress bar: jump there (same path as the remote's seeks). */
    fun onSeekToFraction(fraction: Float) {
        val s = _uiState.value
        if (s.duration <= 0) return
        val target = (s.duration * fraction.coerceIn(0f, 1f)).toLong()
        seekBy(target - s.currentPosition)
        resetHideTimer()
    }

    // --- OSD show/hide ---

    private fun showOsd() {
        _uiState.update { it.copy(osdVisible = true) }
        resetHideTimer()
    }

    private fun hideOsd() {
        hideTimerJob?.cancel()
        _uiState.update {
            it.copy(
                osdVisible = false,
                osdZone = OsdZone.BUTTONS,
                focusedButtonIndex = it.playerButtons.indexOf(PlayerButton.PLAYPAUSE)
            )
        }
    }

    private fun resetHideTimer() {
        hideTimerJob?.cancel()
        hideTimerJob = viewModelScope.launch {
            delay(4000)
            hideOsd()
        }
    }

    private fun moveButtonFocus(sign: Int) {
        _uiState.update {
            val size = it.playerButtons.size
            val next = (it.focusedButtonIndex + sign + size) % size
            it.copy(focusedButtonIndex = next)
        }
    }

    private fun activateButton(button: PlayerButton) {
        when (button) {
            PlayerButton.REWIND -> seekBy(-10_000)
            PlayerButton.PLAYPAUSE -> togglePlayPause()
            PlayerButton.FORWARD -> seekBy(10_000)
            PlayerButton.NEXT -> playNextInZapList()
            PlayerButton.AUDIO -> openTrackMenu(TrackMenuType.AUDIO)
            PlayerButton.SUBTITLE -> openTrackMenu(TrackMenuType.SUBTITLE)
            PlayerButton.QUALITY -> openTrackMenu(TrackMenuType.QUALITY)
            // Direct to mini-player, no confirmation - matches Tizen's
            // activatePlayerButton `case 'pip': enterMiniPlayer()`. Only
            // Back (via the exit dialog) asks for confirmation.
            PlayerButton.PIP -> _miniPlayerRequested.value = true
        }
    }

    private fun seekBy(deltaMs: Long) {
        if (isReplay()) {
            seekReplayBy(deltaMs)
            return
        }
        val currentPlayer = player ?: return
        if (!currentPlayer.isCurrentMediaItemSeekable) {
            flash("Avance et retour indisponibles sur ce flux")
            return
        }
        val newPos = (currentPlayer.currentPosition + deltaMs).coerceAtLeast(0)
        currentPlayer.seekTo(newPos)
    }

    /** Port of Tizen's seekHeld (js/player.js): escalating step size on repeated presses within 700ms. */
    private fun seekHeld(sign: Int) {
        val now = System.currentTimeMillis()
        seekStreak = if (sign == lastSeekDirection && now - lastSeekAt < seekHoldResetMs) {
            (seekStreak + 1).coerceAtMost(seekStepsSeconds.size - 1)
        } else {
            0
        }
        lastSeekDirection = sign
        lastSeekAt = now
        seekBy(seekStepsSeconds[seekStreak] * 1000L * sign)
    }

    fun togglePlayPause() {
        if (player?.isPlaying == true) pause() else play()
    }

    fun play() {
        if (_uiState.value.errorMessage != null) retryCurrentStream() else player?.play()
    }

    fun retryCurrentStream() {
        val state = _uiState.value
        if (!activePlayback || state.streamUrl.isEmpty()) return
        if (isLive()) {
            // A manual retry starts again from the quality the user picked.
            val preferred = liveVariants.firstOrNull()?.streamUrl ?: state.streamUrl
            liveVariantIndex = 0
            _uiState.update { it.copy(liveFallbackName = null) }
            loadStream(preferred, state.contentType)
            return
        }
        val position = replaySeekTargetMs ?: playbackPositionMs()
        replayRecovery.reset()
        loadStream(state.streamUrl, state.contentType, position)
    }

    fun pause() {
        player?.pause()
        // Unthrottled - matches Tizen's `pause` listener calling
        // saveProgressNow() directly (js/player.js).
        saveProgress(force = true)
    }

    /** Home/Alexa backgrounds the TV app: keep the item for an explicit resume, without retrying it. */
    fun onAppBackgrounded() {
        appInBackground = true
        backgroundedAtMs = android.os.SystemClock.elapsedRealtime()
        val retryPending = retryJob?.isActive == true
        retryJob?.cancel()
        disarmPauseWatchdog()
        liveStallJob?.cancel()
        liveStartJob?.cancel()
        liveCushionToken++
        _uiState.update { it.copy(isPrebuffering = false) }
        if (isReplay() && activePlayback && player?.currentMediaItem != null && _uiState.value.errorMessage == null) {
            // Same connection slot as live: free it, and pick the program up
            // where it was on return.
            val position = replaySeekTargetMs ?: playbackPositionMs()
            replayResumeAfterBackground = position to (player.playWhenReady || retryPending)
            saveProgress(force = true)
            replaySeekJob?.cancel()
            replayStartJob?.cancel()
            markStreamStopped()
            player.stop()
            return
        }
        if (isLive() && activePlayback && player?.currentMediaItem != null && _uiState.value.errorMessage == null) {
            // A paused live stream still holds the provider's connection slot
            // (often the only one) and a hardware decoder: release both, and
            // pick the channel up again at the live edge on return.
            liveStoppedInBackground = true
            player.stop()
            return
        }
        if (player?.playWhenReady == true) pause()
        if (retryPending) {
            _uiState.update { it.copy(errorMessage = "Lecture interrompue. Réessayez pour reprendre.", isLoading = false) }
        }
    }

    private var liveStoppedInBackground = false

    fun onAppForegrounded() {
        appInBackground = false
        val elapsed = if (backgroundedAtMs == 0L) 0L
            else android.os.SystemClock.elapsedRealtime() - backgroundedAtMs
        backgroundedAtMs = 0L
        replayResumeAfterBackground?.let { (position, playing) ->
            replayResumeAfterBackground = null
            if (activePlayback && isReplay()) {
                replayRecovery.reset()
                playReplayAt(position, playing)
            }
            return
        }
        if (liveStoppedInBackground) {
            liveStoppedInBackground = false
            val url = _uiState.value.streamUrl
            if (activePlayback && isLive() && url.isNotEmpty()) {
                resetLiveRecovery()
                playStream(url)
            }
            return
        }
        if (elapsed >= pauseWatchdogDelayMs && activePlayback && _uiState.value.errorMessage == null &&
            player?.playbackState == Player.STATE_READY && player.playWhenReady.not()) {
            refreshStalePausedStream()
        }
    }

    /** Hardware media buttons work in fullscreen and while the mini-player owns the video. */
    fun onMediaKey(keyCode: Int, keyDown: Boolean): Boolean {
        if (!activePlayback || player?.currentMediaItem == null) return false
        if (keyCode !in setOf(
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            )) return false
        if (!keyDown) return true
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> play()
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayPause()
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(10_000L)
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000L)
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> playNextInZapList()
        }
        return true
    }

    // --- Zap / playlist drawer (js/player.js openEpisodeList/closeEpisodeList) ---

    private fun openEpisodeList() {
        hideTimerJob?.cancel() // suspended while open, same as Tizen
        val s = _uiState.value
        _uiState.update { it.copy(osdZone = OsdZone.EPISODES, episodeFocusIndex = s.zapIndex.coerceAtLeast(0)) }
        requestZapEpg()
    }

    private fun closeEpisodeList() {
        _uiState.update { it.copy(osdZone = OsdZone.BUTTONS) }
        resetHideTimer()
    }

    private fun moveEpisodeFocus(sign: Int) {
        _uiState.update {
            val next = (it.episodeFocusIndex + sign).coerceIn(0, (it.zapList.size - 1).coerceAtLeast(0))
            it.copy(episodeFocusIndex = next)
        }
        requestZapEpg()
    }

    private fun selectEpisodeListItem() {
        val s = _uiState.value
        val item = s.zapList.getOrNull(s.episodeFocusIndex) ?: return
        val url = item.streamUrl ?: return
        _uiState.update { it.copy(osdZone = OsdZone.BUTTONS, zapIndex = s.episodeFocusIndex) }
        loadStreamWithResumeCheck(
            url, s.contentType, item.id, progressType, item.name, s.zapList, s.seriesId, s.seriesName, s.seasonNum,
            posterUrl = item.posterUrl, categoryId = historyCategoryId, categoryName = historyCategoryName
        )
        showOsd()
    }

    private fun playNextInZapList() {
        val s = _uiState.value
        val nextIndex = s.zapIndex + 1
        val next = s.zapList.getOrNull(nextIndex)
        if (next?.streamUrl == null) {
            flash("Aucun contenu suivant")
            return
        }
        _uiState.update { it.copy(zapIndex = nextIndex) }
        loadStreamWithResumeCheck(
            next.streamUrl, s.contentType, next.id, progressType, next.name, s.zapList, s.seriesId, s.seriesName, s.seasonNum,
            posterUrl = next.posterUrl, categoryId = historyCategoryId, categoryName = historyCategoryName
        )
    }

    // -------------------------------------------------------------------
    // Episode/season auto-advance (js/player.js handlePlaybackEnded /
    // checkNextSeasonOrExit, js/modals.js openNextSeasonDialog) - fires when
    // playback reaches its natural end (not on manual "Suivant"/exit).
    // -------------------------------------------------------------------

    private fun handlePlaybackEnded() {
        val s = _uiState.value
        if (s.seriesId == null) return // movies/live: nothing auto-happens on end, same as Tizen
        if (s.zapIndex in 0 until s.zapList.size - 1) {
            playNextInZapList()
            return
        }
        val seasonNum = s.seasonNum ?: return
        checkNextSeasonOrExit(s.seriesId, seasonNum)
    }

    private fun checkNextSeasonOrExit(seriesId: String, seasonNum: Int) {
        val fetch = fetchSeriesEpisodes
        if (fetch == null) {
            _exitRequested.value = true
            return
        }
        val myToken = ++nextSeasonCheckToken
        nextSeasonCheckJob?.cancel()
        nextSeasonCheckJob = viewModelScope.launch {
            val result = fetch(seriesId)?.nextSeasonAfter(seasonNum)
            // Stale if the user already left/zapped elsewhere while this was in flight.
            if (myToken != nextSeasonCheckToken || _uiState.value.seriesId != seriesId) return@launch
            if (result == null || result.episodes.isEmpty()) {
                _exitRequested.value = true
            } else {
                _nextSeasonPrompt.value = NextSeasonPrompt(result.seasonNum, result.episodes)
            }
        }
    }

    /** Rebuilds zapList/zapIndex from the authoritative season data (see loadStreamWithResumeCheck) - discarded if stale by the time it resolves. */
    private fun rebuildZapListForSeries(seriesId: String, seasonNum: Int, contentId: String?) {
        val fetch = fetchSeriesEpisodes ?: return
        val myToken = ++zapListRebuildToken
        zapListRebuildJob?.cancel()
        zapListRebuildJob = viewModelScope.launch {
            val episodes = fetch(seriesId)?.episodesBySeason?.get(seasonNum) ?: return@launch
            if (myToken != zapListRebuildToken || _uiState.value.seriesId != seriesId || episodes.isEmpty()) return@launch
            _uiState.update { it.copy(zapList = episodes, zapIndex = episodes.indexOfFirst { z -> z.id == contentId }) }
        }
    }

    /**
     * Back on the next-season dialog behaves like "Non" (js/modals.js
     * handleNextSeasonDialogKey: keyCode 10009/8 -> confirmNextSeasonDialog('no')).
     */
    fun confirmNextSeason(playIt: Boolean) {
        val prompt = _nextSeasonPrompt.value
        _nextSeasonPrompt.value = null
        if (!playIt || prompt == null) {
            _exitRequested.value = true
            return
        }
        val first = prompt.episodes.first()
        val url = first.streamUrl
        if (url == null) {
            _exitRequested.value = true
            return
        }
        val s = _uiState.value
        loadStreamWithResumeCheck(
            url, s.contentType, first.id, progressType, first.name, prompt.episodes, s.seriesId, s.seriesName, prompt.seasonNum,
            posterUrl = first.posterUrl, categoryId = historyCategoryId, categoryName = historyCategoryName
        )
    }

    private fun flash(message: String) {
        flashMessageJob?.cancel()
        _uiState.update { it.copy(flashMessage = message) }
        flashMessageJob = viewModelScope.launch {
            delay(2500)
            _uiState.update { it.copy(flashMessage = null) }
        }
    }

    // --- Exit / mini-player confirmation (js/modals.js openResumeDialog-style) ---

    private fun openExitDialog() {
        hideTimerJob?.cancel()
        _uiState.update { it.copy(showExitDialog = true, exitDialogFocusIndex = 0) }
    }

    private fun closeExitDialog() {
        _uiState.update { it.copy(showExitDialog = false) }
        if (_uiState.value.osdVisible) resetHideTimer()
    }

    private fun toggleExitDialogFocus() {
        _uiState.update { it.copy(exitDialogFocusIndex = if (it.exitDialogFocusIndex == 0) 1 else 0) }
    }

    private fun confirmExitDialog() {
        val goMini = _uiState.value.exitDialogFocusIndex == 0
        _uiState.update { it.copy(showExitDialog = false) }
        if (goMini) _miniPlayerRequested.value = true else _exitRequested.value = true
    }

    // --- Audio/subtitle track menu ---

    private fun openTrackMenu(type: TrackMenuType) {
        hideTimerJob?.cancel()
        val options = if (type == TrackMenuType.QUALITY) _uiState.value.liveQualities else buildTrackOptions(type)
        _uiState.update {
            it.copy(
                trackMenuType = type,
                trackMenuFocusIndex = options.indexOfFirst { o -> o.isSelected }.coerceAtLeast(0),
                audioTracks = if (type == TrackMenuType.AUDIO) options else it.audioTracks,
                subtitleTracks = if (type == TrackMenuType.SUBTITLE) options else it.subtitleTracks
            )
        }
    }

    private fun closeTrackMenu() {
        _uiState.update { it.copy(trackMenuType = null) }
    }

    private fun moveTrackMenuFocus(sign: Int) {
        _uiState.update { state ->
            val list = state.trackMenuOptions()
            if (list.isEmpty()) return@update state
            val next = (state.trackMenuFocusIndex + sign).coerceIn(0, list.size - 1)
            state.copy(trackMenuFocusIndex = next)
        }
    }

    private fun confirmTrackMenuSelection() {
        val s = _uiState.value
        val type = s.trackMenuType ?: return
        val option = s.trackMenuOptions(type).getOrNull(s.trackMenuFocusIndex) ?: return
        if (type == TrackMenuType.QUALITY) {
            closeTrackMenu()
            option.id?.let(::switchLiveQuality)
            showOsd()
            return
        }
        if (type == TrackMenuType.AUDIO) preferredAudioLabel = option.label else preferredSubtitleLabel = option.label
        applyTrackOverride(type, option)
        saveTrackPreference()
        closeTrackMenu()
        showOsd()
    }

    private fun buildTrackOptions(type: TrackMenuType): List<TrackOption> {
        val tracks = player?.currentTracks ?: return emptyList()
        val trackType = if (type == TrackMenuType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val options = mutableListOf<TrackOption>()
        var anySelected = false
        for ((groupIndex, group) in tracks.groups.withIndex()) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val label = format.label ?: format.language?.uppercase() ?: "Piste ${options.size + 1}"
                val selected = group.isTrackSelected(i)
                if (selected) anySelected = true
                options += TrackOption(id = "$groupIndex:$i", label = label, isSelected = selected, language = format.language)
            }
        }
        if (type == TrackMenuType.SUBTITLE) {
            options.add(0, TrackOption(id = null, label = "Désactivés", isSelected = !anySelected))
        }
        return options
    }

    private fun applyTrackOverride(type: TrackMenuType, option: TrackOption) {
        val exoPlayer = player ?: return
        val trackType = if (type == TrackMenuType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        if (option.id == null) {
            // "Désactivés"
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .clearOverridesOfType(trackType)
                .build()
            _uiState.update { it.copy(currentSubtitleLabel = "Désactivés") }
            return
        }
        val (groupIndex, trackIndex) = option.id.split(":").map { it.toInt() }
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        _uiState.update {
            if (type == TrackMenuType.AUDIO) it.copy(currentAudioLabel = option.label)
            else it.copy(currentSubtitleLabel = option.label)
        }
    }

    // -------------------------------------------------------------------
    // Progress persistence
    // -------------------------------------------------------------------

    private fun recordStartedMedia() {
        val useCase = getRecentlyWatchedUseCase ?: return
        val id = contentId ?: return
        val state = _uiState.value
        if (!activePlayback || state.streamUrl.isEmpty() || historyRecordedGeneration == playbackGeneration) return
        val playingUri = player?.currentMediaItem?.localConfiguration?.uri.toString()
        if (playingUri != state.streamUrl && playingUri != replayPlayingUrl) return
        val generation = playbackGeneration
        historyRecordedGeneration = generation
        val type = progressType
        val categoryId = historyCategoryId
        // An episode records its series as category, whatever list it was started from.
        val categoryName = state.seriesName?.takeIf { state.seriesId != null && it.isNotBlank() } ?: historyCategoryName
        val posterUrl = historyPosterUrl
        val extension = android.net.Uri.parse(state.streamUrl).lastPathSegment
            ?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            try {
                historyWriteMutex.withLock {
                    useCase.addToHistory(
                        streamId = id,
                        type = type,
                        name = state.contentName,
                        categoryId = categoryId,
                        categoryName = categoryName,
                        posterUrl = posterUrl,
                        seriesId = state.seriesId,
                        seasonNumber = state.seasonNum,
                        containerExtension = extension
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("BtvHistory", "Write failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private data class ProgressSnapshot(
        val id: String,
        val type: String,
        val positionMs: Long,
        val durationMs: Long,
        val extension: String?
    )

    private fun progressSnapshot(): ProgressSnapshot? {
        val id = contentId ?: return null
        if (!activePlayback || isLive()) return null
        val current = player ?: return null
        val duration = if (isReplay()) replay?.durationMs ?: return null else current.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return null
        // A replay restarting at a new minute reports 0 until it plays: keep what was reached.
        val position = (if (isReplay()) replaySeekTargetMs ?: playbackPositionMs() else current.currentPosition).coerceAtLeast(0L)
        val extension = _uiState.value.streamUrl.substringBefore('?').substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() }
        return ProgressSnapshot(id, progressType, position, duration, extension)
    }

    private fun persistProgressSnapshot(snapshot: ProgressSnapshot?): Job? {
        val useCase = getPlaybackProgressUseCase ?: return null
        snapshot ?: return null
        return viewModelScope.launch {
            progressSaveMutex.withLock {
                com.btv.util.guarded("BtvProgress", "Progress save") {
                    useCase.saveProgress(
                        streamId = snapshot.id,
                        type = snapshot.type,
                        progressMs = snapshot.positionMs,
                        durationMs = snapshot.durationMs,
                        containerExtension = snapshot.extension
                    )
                }
            }
        }
    }

    private fun invalidatePlayback(): Long {
        liveStoppedInBackground = false
        liveEpgJob?.cancel()
        zapEpgJob?.cancel()
        playbackGeneration++
        activePlayback = false
        retryJob?.cancel()
        resumeCheckJob?.cancel()
        disarmPauseWatchdog()
        liveVariantJob?.cancel()
        liveStallJob?.cancel()
        liveStartJob?.cancel()
        nextSeasonCheckToken++
        zapListRebuildToken++
        nextSeasonCheckJob?.cancel()
        zapListRebuildJob?.cancel()
        replaySeekJob?.cancel()
        replayStartJob?.cancel()
        replaySeekTargetMs = null
        replayResumeAfterBackground = null
        return playbackGeneration
    }

    private fun startProgressTracking() {
        viewModelScope.launch {
            while (true) {
                delay(500)
                player?.let {
                    val program = replay.takeIf { isReplay() }
                    _uiState.update { state ->
                        if (program != null) state.copy(
                            currentPosition = replaySeekTargetMs ?: playbackPositionMs(),
                            duration = program.durationMs,
                            bufferedPosition = replaySegmentStartMs() + it.bufferedPosition
                        ) else state.copy(
                            currentPosition = it.currentPosition,
                            duration = it.duration,
                            bufferedPosition = it.bufferedPosition
                        )
                    }
                    if (activePlayback) saveProgress()
                }
            }
        }
    }

    private fun saveProgress(force: Boolean = false): Job? {
        val snapshot = progressSnapshot() ?: return null
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressSaveAt < progressSaveIntervalMs) return null
        lastProgressSaveAt = now
        return persistProgressSnapshot(snapshot)
    }

    /**
     * "Sortir" - stops playback but keeps the same player instance around
     * for next time (Tizen reuses the same <video>/AVPlay element rather
     * than tearing it down on every exit). Returns the final progress write,
     * which must complete before the account scope is cleared or switched.
     */
    fun stopAndExit(): Job? {
        val finalSave = saveProgress(force = true)
        invalidatePlayback()
        markStreamStopped()
        player?.stop()
        player?.clearMediaItems()
        contentId = null
        replay = null
        _uiState.value = PlayerUiState()
        _resumePrompt.value = null
        _nextSeasonPrompt.value = null
        return finalSave
    }

    override fun onCleared() {
        // Must survive viewModelScope being cancelled right around this call
        // (same class of bug as the DataStore writes fixed earlier) - loses
        // the last few seconds otherwise, regressing "Continuer à regarder".
        val snapshot = progressSnapshot()
        val useCase = getPlaybackProgressUseCase
        invalidatePlayback()
        if (snapshot != null && useCase != null) {
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                progressSaveMutex.withLock {
                    com.btv.util.guarded("BtvProgress", "Final progress save") { useCase.saveProgress(
                        streamId = snapshot.id,
                        type = snapshot.type,
                        progressMs = snapshot.positionMs,
                        durationMs = snapshot.durationMs,
                        containerExtension = snapshot.extension
                    ) }
                }
            }
        }
        mediaSession?.release()
        player?.release()
        super.onCleared()
    }
}

/** The HTTP status behind a playback failure, if the panel answered with one. */
private fun PlaybackException.httpResponseCode(): Int? {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

/** The archive URL format each account accepted, for the rest of the session. */
private object ReplayFormatMemory {
    private val formats = java.util.concurrent.ConcurrentHashMap<String, TimeshiftFormat>()
    fun get(accountKey: String): TimeshiftFormat? = formats[accountKey]
    fun put(accountKey: String, format: TimeshiftFormat) {
        formats[accountKey] = format
    }
}
