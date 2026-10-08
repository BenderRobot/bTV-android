package com.btv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btv.data.cache.CatalogCache
import com.btv.data.store.ParentalControl
import com.btv.data.store.isAdultCategoryName
import com.btv.ui.parental.PinFlow
import com.btv.data.cache.EpgProgramInfo
import com.btv.data.cache.EpgCacheSnapshot
import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamEpgListing
import com.btv.data.model.XtreamChannel
import com.btv.data.model.XtreamSeries
import com.btv.data.model.XtreamVod
import com.btv.ui.player.ZapItem
import com.btv.ui.player.buildLiveFallbackChain
import com.btv.ui.player.liveChannelKey
import com.btv.ui.player.liveDisplayName
import com.btv.data.repository.AuthRepository
import com.btv.data.repository.NewEpisodesRepository
import com.btv.data.repository.TmdbRepository
import com.btv.data.repository.trimToArchiveWindow
import com.btv.data.store.CatalogSection
import com.btv.data.store.PreferencesStore
import com.btv.domain.usecase.GetFavoritesUseCase
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase
import com.btv.domain.usecase.ToggleFavoriteUseCase
import com.btv.util.decodeEpgText
import com.btv.util.resolveExtension
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BrowseUiStateExtended(
    val uiState: BrowseUiState = BrowseUiState(),
    val favoriteIds: Set<String> = emptySet(),
    val inProgressIds: Set<String> = emptySet()
)

private const val CATEGORY_CONTINUE_WATCHING = "continue_watching"
private const val CATEGORY_FAVORITES = "favorites"
private const val CATEGORY_REPLAY_CONTINUE = "replay_continue"
// Rediffusion's "en cours" entry: launched through startOverReplay, never part of the list.
internal const val REPLAY_ON_AIR_ID = "replay_on_air"
private const val CATEGORY_SHOW_ALL = "show_all"
private const val CATEGORY_RECENTLY_VIEWED = CATEGORY_RECENTLY_VIEWED_ID
// Tizen trackRecent keeps the 30 most recent entries.
private const val RECENTLY_VIEWED_CAP = 30
private const val CATEGORY_RECENTLY_ADDED = "recently_added"

// Port of Tizen's fetchAllItems display cap (js/browse.js ALL_CAP=300):
// "Tout afficher" can represent the whole catalog, so results are capped.
// Like Tizen, it reads the one unfiltered list - but decoded as a stream
// (parsing it in one go is what caused the earlier OOM crash). Scanning
// category by category remains the fallback; real panels answer that
// 93-request burst with HTTP 429.
private const val SHOW_ALL_CAP = 300
private const val SHOW_ALL_PROGRESS_STEP = 2_000

private data class ShowAllIndexedCategory(val refs: List<ShowAllRef>, val preview: List<ContentItem>)

// Categories are fetched concurrently (bounded, not one-at-a-time) when
// building "Tout afficher" - most Xtream panels are modest boxes, not CDNs,
// so an unbounded fan-out across dozens of categories risked overwhelming
// them (and being throttled/slower for it) as much as it risked overwhelming
// the client.
private const val SHOW_ALL_CONCURRENCY = 3

// Exact port of Tizen's computeRecentlyAdded (js/data.js): first 12
// categories (server/sidebar order), items concatenated and sorted by
// added/last_modified desc, capped at 40 - deliberately small so it's
// never a full-catalog scan.
private const val RECENTLY_ADDED_CATEGORY_SCAN = 12
private const val RECENTLY_ADDED_CAP = 40

// Mirrors Tizen's CHANNEL_LIST_EAGER_EPG_LIMIT / 150ms pacing (js/browse.js
// enrichChannelListWithEpg): firing one EPG request per channel at once (up
// to 40 simultaneously) is what caused visible lag on category selection -
// so channels are enriched one at a time in the background instead.
// A guide rewritten to disk at most this often per channel.
private const val LIVE_EPG_PERSIST_INTERVAL_MS = 5 * 60 * 1000L
private const val LIVE_EPG_ENRICH_DELAY_MS = 150L
// Rediffusion: the panel is asked once the focus rests this long on a channel,
// and each neighbour is warmed up this long after the previous request.
private const val REPLAY_FOCUS_SETTLE_MS = 350L
private const val REPLAY_PREFETCH_DELAY_MS = 1_500L

class BrowseViewModel(
    private val authRepository: AuthRepository? = null,
    private val session: AuthSession? = null,
    private val preferencesStore: PreferencesStore? = null,
    private val getFavoritesUseCase: GetFavoritesUseCase? = null,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase? = null,
    private val getPlaybackProgressUseCase: GetPlaybackProgressUseCase? = null,
    private val getRecentlyWatchedUseCase: GetRecentlyWatchedUseCase? = null,
    private val showAllSnapshotStore: ShowAllSnapshotStore? = null,
    private val newEpisodesRepository: NewEpisodesRepository? = null,
    private val liveEpgDiskCache: com.btv.data.repository.LiveEpgDiskCache? = null,
    private val replayArchiveStore: com.btv.data.repository.ReplayArchiveStore? = null
) : ViewModel() {

    private val catalogGeneration = CatalogCache.generationToken()

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState

    /** Parental control PIN dialog state, shared with BrowseScreen. */
    val pinFlow: PinFlow? = preferencesStore?.let { PinFlow(ParentalControl(it), viewModelScope) }
    private var adultGateJob: Job? = null
    // Adult Live categories not unlocked in Réglages: out of the sidebar, "Tout afficher" and search.
    private var lockedLiveCategoryIds: Set<String> = emptySet()

    /** Completed ids of the current media type, observed from Room: a manual
     * Vu/Non vu and a playback reaching 92% both update every poster. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val watchedIds: StateFlow<Set<String>> = _uiState.map { it.mediaType }.distinctUntilChanged()
        .flatMapLatest { type ->
            val useCase = getPlaybackProgressUseCase
            if (useCase == null || (type != ContentType.VOD && type != ContentType.SERIES && type != ContentType.REPLAY)) flowOf(emptySet())
            else useCase.getCompletedIds(type.name)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Unacknowledged new episodes per favorite series id. */
    val newEpisodeCounts: StateFlow<Map<String, Int>> =
        (newEpisodesRepository?.newEpisodeCounts() ?: flowOf(emptyMap()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Tizen flashAppToast after toggling Vu/Non vu.
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages

    private val _favoriteIds = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    val favoriteIds: StateFlow<Set<Pair<String, String>>> = _favoriteIds

    private val _selectedContentItem = MutableStateFlow<ContentItem?>(null)
    val selectedContentItem: StateFlow<ContentItem?> = _selectedContentItem

    // Cancelled/replaced every time a new live category's channel list loads,
    // same as Tizen's `channelListEnrichToken` guarding against a stale
    // enrichment loop still running after the user switched category.
    private var epgEnrichJob: Job? = null
    private var liveEpgJob: Job? = null

    // Cancelled/replaced on every category switch: without this, a slow
    // load still in flight (e.g. "Nouveautés"/"Tout afficher" aggregating
    // many categories) can finish AFTER the user has already moved to a
    // different, faster category and overwrite its content with stale
    // results - the title updates immediately (from selectedCategoryId)
    // but `contents` silently reverts to the old category's items.
    private var contentLoadJob: Job? = null

    // The complete, NEVER-capped result of the current rail (js/browse.js
    // railFullList) - `contents` itself may be a capped/filtered view of
    // this (e.g. "Tout afficher"'s first 300). Content search always
    // re-filters from this list, never from `contents`, otherwise anything
    // beyond the display cap could never be found no matter what's typed
    // (cf. js/browse.js applyContentSearchFilter's own comment on this
    // exact trap).
    private var currentContentFullList: List<ContentItem> = emptyList()
    // "Tout afficher" retains searchable identities, not 70k full cards.
    // Cards are resolved from their category only for the visible 300 matches.
    private var showAllIndex: List<ShowAllRef>? = null
    private var showAllInitialItems: List<ContentItem> = emptyList()
    private var showAllScanFailures = 0
    // True when showAllIndex came from the unfiltered list: search results
    // are then rebuilt from that list too, never from per-category requests.
    private var showAllUnfiltered = false
    private var showAllSearchJob: Job? = null

    // Cancelled/replaced on every preview - mirrors Tizen's synopsisToken +
    // 200ms debounce in updateSynopsisPanel (js/browse.js).
    private var vodInfoJob: Job? = null

    // Cosmetic TMDB cast-photo enrichment (js/data.js fetchTmdbCast) - own
    // job/debounce, independent of vodInfoJob, since it can run for series
    // (which have no get_vod_info call at all) just as well as for movies.
    private var castPhotoJob: Job? = null
    private val tmdbRepository = TmdbRepository()

    init {
        loadFavorites()
    }

    /**
     * Real server data is available for LIVE/VOD/SERIES/REPLAY (the latter
     * via get_simple_data_table, js/data.js fetchReplayPrograms). FAVORITES
     * is a local/Room concern and still falls back to the mock generator.
     */
    private fun isRealDataCapable(type: ContentType): Boolean {
        return authRepository != null && session != null &&
            (type == ContentType.LIVE || type == ContentType.VOD || type == ContentType.SERIES || type == ContentType.REPLAY)
    }

    fun setContentType(type: ContentType) {
        if (_uiState.value.contentType == type && _uiState.value.categories.isNotEmpty()) return
        _uiState.update { it.copy(contentType = type) }
        when {
            type == ContentType.FAVORITES -> loadFavoritesCatalog()
            type == ContentType.REPLAY && isRealDataCapable(type) -> loadReplayCatalog()
            isRealDataCapable(type) -> loadRealCatalog(type)
            else -> loadMockData()
        }
    }

    fun selectCategory(categoryId: String) {
        contentLoadJob?.cancel()
        showAllSearchJob?.cancel()
        showAllIndex = null
        showAllInitialItems = emptyList()
        showAllScanFailures = 0
        showAllUnfiltered = false
        liveEpgJob?.cancel()
        // Clear the previous category's contents/backdrop immediately -
        // otherwise the old submenu stays visible (backdrop + rail) for the
        // whole loading gap, since only selectedContentId was reset here and
        // `contents`/`selectedContent` themselves weren't touched until the
        // new load's own update landed.
        currentContentFullList = emptyList()
        _uiState.update { state ->
            state.copy(
                selectedCategoryId = categoryId,
                selectedContentId = null,
                selectedContent = null,
                contents = emptyList(),
                contentSearch = "",
                isLoading = true,
                loadingProgress = null,
                error = if (state.retryTarget == BrowseRetryTarget.CONTENT) null else state.error,
                retryTarget = if (state.retryTarget == BrowseRetryTarget.CONTENT) null else state.retryTarget,
                liveEpgChannelId = null,
                liveEpgPrograms = emptyList(),
                isLiveEpgLoading = false,
                liveEpgError = null,
                contentDrillStack = emptyList()
            )
        }
        when {
            _uiState.value.contentType == ContentType.FAVORITES ->
                _uiState.value.categories.find { it.id == categoryId }?.let { loadFavoritesContent(it.type) }
            _uiState.value.contentType == ContentType.REPLAY && isRealDataCapable(ContentType.REPLAY) ->
                loadReplayContentsForCategory(categoryId)
            isRealDataCapable(_uiState.value.contentType) -> loadRealContentsForCategory(_uiState.value.contentType, categoryId)
            else -> loadContentsForCategory(categoryId)
        }
    }

    fun retryCurrentLoad() {
        val state = _uiState.value
        when (state.retryTarget) {
            BrowseRetryTarget.CATALOG -> when (state.contentType) {
                ContentType.REPLAY -> loadReplayCatalog()
                ContentType.LIVE, ContentType.VOD, ContentType.SERIES -> loadRealCatalog(state.contentType, preserveSelection = true)
                else -> Unit
            }
            BrowseRetryTarget.CONTENT -> state.selectedCategoryId?.let(::selectCategory)
            null -> Unit
        }
    }

    /**
     * Moving focus onto a card - highlight + backdrop update only, same as
     * Tizen's updateSynopsisPanel (js/browse.js): NOT a "play" action. Also
     * kicks off the debounced get_vod_info fetch (see fetchVodInfoDebounced)
     * since arrow-browsing must never fire a network call per card passed
     * through.
     */
    fun previewContent(contentId: String) {
        val content = _uiState.value.contents.find { it.id == contentId } ?: return
        _uiState.update { it.copy(selectedContentId = contentId, selectedContent = content) }
        loadSelectedDetails(content)
    }

    /**
     * Tizen updateSynopsisPanel runs for every selection, including the one a
     * category opens on; here it used to run only when focus moved, so the
     * first item of a list never got its synopsis or cast.
     */
    fun loadSelectedDetails() {
        _uiState.value.selectedContent?.let(::loadSelectedDetails)
    }

    private fun loadSelectedDetails(content: ContentItem) {
        // mediaType, not contentType: a film opened from Favoris needs its details too.
        if (_uiState.value.mediaType == ContentType.VOD && content.contentKind == ContentKind.PLAYABLE &&
            content.plot.isNullOrEmpty()) {
            fetchVodInfoDebounced(content.id)
        } else {
            // Series (and any VOD whose plot/cast is already cached from a
            // previous visit) never go through fetchVodInfoDebounced, so
            // this is the only place they'd ever get a cast-photo lookup.
            fetchCastPhotosDebounced(content)
        }
    }

    fun toggleSelectedWatched() {
        val state = _uiState.value
        val item = state.selectedContent ?: return
        if (!item.canMarkWatched(state.mediaType)) return
        val useCase = getPlaybackProgressUseCase ?: return
        val type = state.mediaType.name
        val extension = android.net.Uri.parse(item.streamUrl.orEmpty()).lastPathSegment
            ?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        val isSeason = item.contentKind == ContentKind.SEASON
        // A season reads as watched only when all its episodes are, so the
        // toggle marks all of them watched unless that is already the case.
        val seasonWatched = isSeason && watchedIds.value.containsAll(item.episodeIds)
        viewModelScope.launch {
            val watched = try {
                if (isSeason) {
                    useCase.setWatched(item.episodeIds, type, !seasonWatched)
                    !seasonWatched
                } else useCase.toggleWatched(item.id, type, extension)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("BtvBrowse", "Watched update failed: ${error.javaClass.simpleName}")
                return@launch
            }
            // watchedIds picks the change up from Room.
            _messages.tryEmit(when {
                isSeason && watched -> "Saison marquée comme vue"
                isSeason -> "Saison marquée comme non vue"
                watched -> "Marqué comme vu"
                else -> "Marqué comme non vu"
            })
        }
    }

    /** Each live channel family's remembered quality (see LiveChannelGroups). */
    val liveQualityChoices: StateFlow<Map<String, com.btv.data.store.LiveQualityChoice>> =
        (preferencesStore?.liveQualityChoices ?: kotlinx.coroutines.flow.flowOf(emptyMap()))
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())

    private var explicitQualityLaunch = false

    /** True once for a launch made from the Live panel's quality buttons. */
    fun consumeExplicitQualityLaunch(): Boolean = explicitQualityLaunch.also { explicitQualityLaunch = false }

    /** A quality picked in the Live panel: remembered for the whole channel family, then played. */
    fun openLiveQuality(channelKey: String, variant: ContentItem) {
        preferencesStore?.let { store ->
            viewModelScope.launch {
                store.rememberLiveQuality(channelKey, com.btv.data.store.LiveQualityChoice(variant.id, variant.name))
            } // PreferencesStore writes never throw (safeEdit)
        }
        openContent(variant.id)
        explicitQualityLaunch = true
    }

    /** Explicit "play this" action (Enter/Center/click) - was previously conflated with preview. */
    fun openContent(contentId: String) {
        explicitQualityLaunch = false
        val content = _uiState.value.contents.find { it.id == contentId } ?: return
        when (content.contentKind) {
            ContentKind.SERIES -> openSeriesSeasons(content)
            ContentKind.SEASON -> openSeasonEpisodes(content)
            ContentKind.PLAYABLE -> {
                _uiState.update { it.copy(selectedContentId = contentId, selectedContent = content) }
                launchPlayable(content)
            }
        }
    }

    /** Hands [content] to the player, behind the PIN for an adult channel. */
    private fun launchPlayable(content: ContentItem) {
        val flow = pinFlow
        val mediaType = _uiState.value.mediaType
        if ((mediaType != ContentType.LIVE && mediaType != ContentType.REPLAY) || flow == null) {
            _selectedContentItem.value = content  // Navigate to player with content
            return
        }
        // Adult channels ask for the PIN every time, wherever they
        // are launched from (category, favourites, history, search,
        // and their archive in Rediffusion).
        adultGateJob?.cancel()
        adultGateJob = viewModelScope.launch {
            val adult = if (mediaType == ContentType.REPLAY) isAdultReplayChannel(replayChannelIdOf(content.id)) else isAdultLiveChannel(content.id)
            if (adult) {
                flow.require("Chaîne réservée aux adultes.") { _selectedContentItem.value = content }
            } else {
                _selectedContentItem.value = content
            }
        }
    }

    /** Fail-closed: a channel whose category can't be established asks for the PIN too. */
    private suspend fun isAdultLiveChannel(channelId: String): Boolean {
        val session = session ?: return false
        val repo = authRepository ?: return false
        val verdict = AdultChannelIndex.check(channelId, _uiState.value.selectedCategoryId, session, repo) { categoryId ->
            fetchLiveStreamsResult(session, repo, categoryId)
        }
        return verdict != AdultChannelIndex.Verdict.NotAdult
    }

    /**
     * A Rediffusion program is judged by its channel's own live category
     * (the sidebar id is a stream id, or "Continuer", never a category).
     */
    private suspend fun isAdultReplayChannel(channelId: String): Boolean {
        val session = session ?: return false
        val repo = authRepository ?: return false
        val channel = replayChannelsById[channelId] ?: return true
        val verdict = AdultChannelIndex.check(channel.streamId, channel.categoryId, session, repo) { categoryId ->
            fetchLiveStreamsResult(session, repo, categoryId)
        }
        return verdict != AdultChannelIndex.Verdict.NotAdult
    }

    /** Adult channels the player must not zap to from a non-adult launch. */
    fun zapExclusions(launchedId: String): Set<String> {
        val adult = AdultChannelIndex.knownAdultChannelIds()
        return if (launchedId in adult) emptySet() else adult
    }

    fun clearSelection() {
        _selectedContentItem.value = null
        // Keep the highlighted channel when returning from the player.
    }

    fun previewLiveEpg(channelId: String, forceRefresh: Boolean = false) {
        val state = _uiState.value
        if (state.contentType != ContentType.LIVE || state.selectedContentId != channelId ||
            (state.liveEpgChannelId == channelId && !forceRefresh)) return
        liveEpgJob?.cancel()
        val categoryId = state.selectedCategoryId
        _uiState.update { it.copy(
            liveEpgChannelId = channelId,
            liveEpgPrograms = if (forceRefresh) it.liveEpgPrograms else emptyList(),
            isLiveEpgLoading = true,
            liveEpgError = null
        ) }
        liveEpgJob = viewModelScope.launch {
            delay(200)
            val result = if (isRealDataCapable(ContentType.LIVE)) fetchEpgListings(channelId, forceRefresh)
            else Result.success(EpgCacheSnapshot(generateMockPrograms(channelId).map {
                EpgProgramInfo(it.title, it.startTime, it.endTime)
            }))
            val snapshot = result.getOrNull()
            val listings = snapshot?.value.orEmpty().map {
                EpgProgram("${channelId}_${it.startTs}", channelId, it.title, startTime = it.startTs, endTime = it.stopTs)
            }
            if (_uiState.value.selectedCategoryId != categoryId || _uiState.value.selectedContentId != channelId) return@launch
            _uiState.update { it.copy(
                liveEpgPrograms = listings,
                isLiveEpgLoading = false,
                liveEpgError = epgErrorMessage(result.isFailure, snapshot?.isStale == true)
            ) }
            snapshot?.value?.let { guide ->
                applyLiveEpg(mapOf(channelId to guide))
                if (!snapshot.isStale) persistLiveEpg(com.btv.data.db.AccountScope.global.key.value, channelId, guide)
            }
        }
    }

    fun retryLiveEpg() {
        _uiState.value.selectedContentId?.let { previewLiveEpg(it, forceRefresh = true) }
    }

    private var epgScreenJob: Job? = null

    fun openEpg(channelId: String, forceRefresh: Boolean = false) {
        epgScreenJob?.cancel()
        if (!isRealDataCapable(ContentType.LIVE)) {
            val programs = generateMockPrograms(channelId)
            _uiState.update { it.copy(epgChannelId = channelId, epgPrograms = programs, epgError = null, isEpgLoading = false) }
            return
        }
        _uiState.update { it.copy(
            epgChannelId = channelId,
            epgPrograms = if (forceRefresh && it.epgChannelId == channelId) it.epgPrograms else emptyList(),
            epgError = null,
            isEpgLoading = true
        ) }
        epgScreenJob = viewModelScope.launch {
            val result = fetchEpgListings(channelId, forceRefresh)
            val snapshot = result.getOrNull()
            val programs = snapshot?.value.orEmpty().map { listing ->
                EpgProgram(
                    id = "${channelId}_${listing.startTs}",
                    channelId = channelId,
                    title = listing.title,
                    startTime = listing.startTs,
                    endTime = listing.stopTs
                )
            }
            if (_uiState.value.epgChannelId != channelId) return@launch
            _uiState.update { it.copy(
                epgPrograms = programs,
                epgError = epgErrorMessage(result.isFailure, snapshot?.isStale == true),
                isEpgLoading = false
            ) }
        }
    }

    fun retryEpg() {
        _uiState.value.epgChannelId?.let { openEpg(it, forceRefresh = true) }
    }

    fun closeEpg() {
        epgScreenJob?.cancel()
        _uiState.update { it.copy(epgChannelId = null, epgPrograms = emptyList(), epgError = null, isEpgLoading = false) }
    }

    private fun epgErrorMessage(failed: Boolean, stale: Boolean): String? = when {
        stale -> "Guide ancien : actualisation impossible. Réessayer."
        failed -> "Impossible de charger le guide TV. Réessayer."
        else -> null
    }

    fun updateCategorySearch(query: String) {
        _uiState.update { it.copy(categorySearch = query) }
    }

    /** Search the compact all-catalog index, or the current category list. */
    fun updateContentSearch(query: String) {
        if (_uiState.value.selectedCategoryId == CATEGORY_SHOW_ALL && isRealDataCapable(_uiState.value.contentType)) {
            showAllSearchJob?.cancel()
            val index = showAllIndex
            when {
                query.isBlank() -> {
                    currentContentFullList = showAllInitialItems
                    _uiState.update { state -> state.copy(
                        contentSearch = query,
                        contents = showAllInitialItems,
                        selectedContentId = showAllInitialItems.firstOrNull()?.id,
                        selectedContent = showAllInitialItems.firstOrNull(),
                        isLoading = index == null,
                        loadingProgress = if (index == null) state.loadingProgress else null,
                        error = if (showAllScanFailures > 0) aggregateLoadError(showAllScanFailures, showAllInitialItems.isNotEmpty())
                            else if (state.retryTarget == BrowseRetryTarget.CATALOG) state.error else null,
                        retryTarget = if (showAllScanFailures > 0) BrowseRetryTarget.CONTENT
                            else if (state.retryTarget == BrowseRetryTarget.CATALOG) state.retryTarget else null
                    ) }
                }
                index == null -> _uiState.update { state -> state.copy(
                    contentSearch = query, contents = emptyList(),
                    selectedContentId = null, selectedContent = null, isLoading = true
                ) }
                else -> {
                    _uiState.update { state -> state.copy(
                        contentSearch = query, contents = emptyList(),
                        selectedContentId = null, selectedContent = null, isLoading = true,
                        loadingProgress = "Recherche dans le catalogue…"
                    ) }
                    showAllSearchJob = viewModelScope.launch {
                        delay(250)
                        resolveShowAllSearch(query, index, _uiState.value.contentType)
                    }
                }
            }
            return
        }
        val filtered = if (query.isBlank()) {
            currentContentFullList.take(SHOW_ALL_CAP)
        } else {
            currentContentFullList.filter { it.name.contains(query, ignoreCase = true) }.take(SHOW_ALL_CAP)
        }
        _uiState.update {
            it.copy(
                contentSearch = query,
                contents = filtered,
                selectedContentId = filtered.firstOrNull()?.id,
                selectedContent = filtered.firstOrNull()
            )
        }
    }

    fun clearContentSearch() {
        updateContentSearch("")
    }

    fun setSidebarVisible(visible: Boolean) {
        _uiState.update { it.copy(isSidebarVisible = visible) }
    }

    private fun selectedCategoryName(): String =
        _uiState.value.categories.find { it.id == _uiState.value.selectedCategoryId }?.name ?: ""

    fun toggleFavorite(contentItem: ContentItem) {
        val state = _uiState.value
        if (!contentItem.canFavorite(state.mediaType)) return
        val categoryName = selectedCategoryName()
        viewModelScope.launch {
            com.btv.util.guarded("BtvBrowse", "Favorite toggle") { toggleFavoriteUseCase?.execute(
                streamId = contentItem.id,
                type = state.mediaType.name,
                name = contentItem.name,
                categoryId = state.selectedCategoryId ?: "",
                categoryName = categoryName,
                posterUrl = contentItem.posterUrl,
                containerExtension = contentItem.streamUrl?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
            ) } ?: return@launch
            // The init-time Room observer already updates favoriteIds.
            if (_uiState.value.selectedCategoryId == state.selectedCategoryId &&
                (state.contentType == ContentType.FAVORITES || state.selectedCategoryId == CATEGORY_FAVORITES)) {
                contentLoadJob?.cancel()
                loadFavoritesContent(state.mediaType)
            }
        }
    }

    fun isFavorite(contentId: String): Boolean {
        return _favoriteIds.value.contains(_uiState.value.mediaType.name to contentId)
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            getFavoritesUseCase?.getAllFavorites()?.collect { favorites ->
                _favoriteIds.value = favorites.map { it.type to it.streamId }.toSet()
                _uiState.update { state ->
                    if (state.contentType != ContentType.FAVORITES) state else state.copy(
                        categories = state.categories.map { category ->
                            category.copy(itemCount = favorites.count { it.type == category.type.name })
                        }
                    )
                }
            }
        }
    }

    private fun screenTitleFor(type: ContentType): String = when (type) {
        ContentType.SERIES -> "Séries"
        ContentType.LIVE -> "En Direct"
        ContentType.FAVORITES -> "Favoris"
        ContentType.REPLAY -> "Rediffusion"
        ContentType.VOD -> "Films"
    }

    private var pinnedCategoryIds: List<String> = emptyList()

    /** Pinning exists where the sidebar lists real panel categories. */
    val canPinCategories: Boolean get() = sectionFor(_uiState.value.contentType) != null

    /** ☰ / long OK in the sidebar: pin a category to the top, or unpin it. */
    fun togglePinnedCategory(categoryId: String) {
        val state = _uiState.value
        val section = sectionFor(state.contentType) ?: return
        val store = preferencesStore ?: return
        val category = state.categories.firstOrNull { it.id == categoryId } ?: return
        if (category.isQuickAccess) return
        viewModelScope.launch {
            pinnedCategoryIds = store.togglePinnedCategory(section, categoryId)
            val current = _uiState.value.categories
            val real = current.filterNot { it.isQuickAccess }.map { it.copy(isPinned = false) }.sortedBy { it.name }
            _uiState.update { it.copy(categories = arrangeCategories(current.filter { c -> c.isQuickAccess }, real, pinnedCategoryIds)) }
            _messages.tryEmit(if (categoryId in pinnedCategoryIds) "« ${category.name} » épinglée en haut" else "« ${category.name} » désépinglée")
        }
    }

    private fun sectionFor(type: ContentType): CatalogSection? = when (type) {
        ContentType.VOD -> CatalogSection.MOVIES
        ContentType.SERIES -> CatalogSection.SERIES
        ContentType.LIVE -> CatalogSection.LIVE
        else -> null
    }

    private fun streamKindFor(type: ContentType): String = when (type) {
        ContentType.VOD -> "movie"
        ContentType.LIVE -> "live"
        else -> "series"
    }

    /**
     * Same set/ordering as Tizen's sidebar (browse.js): Continuer à regarder,
     * Favoris, [Tout afficher deliberately NOT first - see loadShowAllContent],
     * Ajoutés récemment. "Consulté récemment" is an Android-only addition on
     * top of Tizen (which drops it for movies/series specifically to avoid
     * duplicating "Continuer à regarder" - the user wants both here).
     */
    private fun quickAccessCategoriesFor(type: ContentType): List<BrowseCategory> {
        // Tizen (js/browse.js): a live stream has no progress to resume, so the
        // Direct keeps its history in place of "Continuer à regarder".
        val list = if (type == ContentType.LIVE) mutableListOf(
            BrowseCategory(CATEGORY_RECENTLY_VIEWED, "Récemment consultés", 0, isQuickAccess = true),
            BrowseCategory(CATEGORY_FAVORITES, "Favoris", 0, isQuickAccess = true)
        ) else mutableListOf(
            BrowseCategory(CATEGORY_CONTINUE_WATCHING, "Continuer à regarder", 0, isQuickAccess = true),
            BrowseCategory(CATEGORY_FAVORITES, "Favoris", 0, isQuickAccess = true)
        )
        if (type == ContentType.VOD || type == ContentType.SERIES) {
            list += BrowseCategory(CATEGORY_RECENTLY_VIEWED, "Récemment consultés", 0, isQuickAccess = true)
        }
        list += BrowseCategory(CATEGORY_SHOW_ALL, "Tout afficher", 0, isQuickAccess = true)
        if (type == ContentType.VOD || type == ContentType.SERIES) {
            list += BrowseCategory(CATEGORY_RECENTLY_ADDED, "Nouveautés", 0, isQuickAccess = true)
        }
        return list
    }

    // ---------------------------------------------------------------------
    // Real server data
    // ---------------------------------------------------------------------

    /**
     * Only fetches the (lightweight) category list - NOT the full item list.
     * This server's VOD catalog alone has 70k+ movies; fetching the
     * unfiltered `get_vod_streams`/`get_series` list crashed the app with
     * an OutOfMemoryError trying to parse a 30MB+ JSON response in one go.
     * Regular categories are fetched on demand. "Tout afficher" is the
     * exception: its unfiltered response is decoded one item at a time.
     */
    private fun loadRealCatalog(type: ContentType, preserveSelection: Boolean = false) {
        val session = session ?: return
        val repo = authRepository ?: return
        val section = sectionFor(type)
        val generation = catalogGeneration

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, retryTarget = null) }

            // Cache-first, same fallback pattern as Tizen's
            // renderSidebarCategories: read the splash-time preload if it's
            // there, otherwise fetch now and backfill the cache so the next
            // screen that needs this section doesn't hit the network either.
            val categoriesResult: Result<List<XtreamCategory>> = if (section != null) {
                CatalogCache.loadCategories(section, generation) {
                    when (type) {
                        ContentType.LIVE -> repo.getLiveCategories(session)
                        ContentType.VOD -> repo.getVodCategories(session)
                        ContentType.SERIES -> repo.getSeriesCategories(session)
                        else -> Result.success(emptyList())
                    }
                }
            } else Result.success(emptyList())

            // Same filters the user manually curates on Tizen (Réglages >
            // Catégories masquées), plus an Android-only language-prefix
            // filter layered on top: both are applied HERE, once, so every
            // consumer of `categories` (sidebar, and therefore every rail
            // fetched per-category below) is filtered consistently - unlike
            // the Tizen reference, which only filters the sidebar and lets
            // "Tout afficher"/"Ajoutés récemment" bypass it.
            val hiddenIds = section?.let { preferencesStore?.hiddenCategoryIds(it)?.first() } ?: emptySet()
            val disabledPrefixes = preferencesStore?.disabledLanguagePrefixes?.first() ?: emptySet()
            // Parental control: adult Live categories stay hidden until
            // unlocked with the PIN in Réglages (and still ask it to play).
            val revealedAdult = if (type == ContentType.LIVE) {
                preferencesStore?.revealedAdultCategoryIds?.first() ?: emptySet()
            } else emptySet()
            if (type == ContentType.LIVE) {
                lockedLiveCategoryIds = categoriesResult.getOrElse { emptyList() }
                    .filter { isAdultCategoryName(it.categoryName) && it.categoryId !in revealedAdult }
                    .mapTo(HashSet()) { it.categoryId }
            }

            val realCategoryList = categoriesResult.getOrElse { emptyList() }
                .filter { it.categoryId.isNotBlank() && it.categoryName.isNotBlank() }
                .distinctBy { it.categoryId }
                .filterNot { it.categoryId in hiddenIds }
                .filterNot { type == ContentType.LIVE && it.categoryId in lockedLiveCategoryIds }
                .filterNot { extractLanguagePrefix(it.categoryName) in disabledPrefixes }
                .sortedBy { it.categoryName }

            pinnedCategoryIds = section?.let { preferencesStore?.pinnedCategoryIds(it)?.first() }.orEmpty()
            val categories = arrangeCategories(
                quickAccessCategoriesFor(type),
                realCategoryList.map { BrowseCategory(it.categoryId, it.categoryName) },
                pinnedCategoryIds
            )
            val previousSelection = _uiState.value.selectedCategoryId
            val selectedCategoryId = previousSelection.takeIf { id -> preserveSelection && categories.any { it.id == id } }
                ?: categories.firstOrNull()?.id

            _uiState.update {
                it.copy(
                    categories = categories,
                    selectedCategoryId = selectedCategoryId,
                    screenTitle = screenTitleFor(type),
                    isLoading = false,
                    error = if (categoriesResult.isFailure) "Impossible de charger les catégories du serveur IPTV." else null,
                    retryTarget = if (categoriesResult.isFailure) BrowseRetryTarget.CATALOG else null
                )
            }

            if (!preserveSelection || selectedCategoryId != previousSelection || _uiState.value.contents.isEmpty()) {
                selectedCategoryId?.let { loadRealContentsForCategory(type, it) }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Rediffusion / catch-up (js/browse.js: browseSectionKey === 'replay') -
    // categories here are archivable CHANNELS, not Xtream categories: only
    // live channels with tv_archive enabled offer any history at all.
    // ---------------------------------------------------------------------

    // Every visible archive channel (all qualities), and the sidebar rows they fold into.
    private var replayChannelsById: Map<String, XtreamChannel> = emptyMap()
    private var replayGroupsById: Map<String, ReplayChannelGroup> = emptyMap()
    private var replayPruned = false
    private var replayCatalogJob: Job? = null
    // Panel clock vs UTC, learned from any guide entry (see panelOffsetMs).
    private var replayPanelOffsetMs: Long? = null
    // The on-air program's start in panel time: its URL is only built at launch (duration = so far).
    private var replayOnAirPanelStart: String? = null

    /** Rediffusion progress per program id (0..1), for the list's bars and "Continuer". */
    @OptIn(ExperimentalCoroutinesApi::class)
    val replayProgress: StateFlow<Map<String, Float>> = _uiState.map { it.contentType == ContentType.REPLAY }.distinctUntilChanged()
        .flatMapLatest { isReplay ->
            val useCase = getPlaybackProgressUseCase
            if (!isReplay || useCase == null) flowOf(emptyMap())
            else useCase.getInProgress(ContentType.REPLAY.name).map { list ->
                list.associate { it.streamId to (it.progressPercent / 100f).coerceIn(0f, 1f) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Archivable channels: memory (this session), else the disk copy at
     * once, then the panel's list read in the background - the sidebar only
     * changes if the panel's list did.
     */
    private fun loadReplayCatalog() {
        val session = session ?: return
        val repo = authRepository ?: return
        contentLoadJob?.cancel()
        replayCatalogJob?.cancel()
        // Its own job: showing the channels starts the first channel's load,
        // which replaces contentLoadJob - the background refresh must survive it.
        replayCatalogJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, retryTarget = null) }
            val accountKey = com.btv.data.db.AccountScope.global.key.value
            pruneReplayArchiveOnce()
            CatalogCache.peekArchiveChannels()?.let { channels ->
                showReplayChannels(channels, failed = false, keepSelection = false)
                return@launch
            }
            val disk = replayDisk("channels read") { store -> accountKey?.let { store.readChannels(it) } }
            if (disk != null) showReplayChannels(disk.value, failed = false, keepSelection = false)
            val fetched = CatalogCache.loadArchiveChannels(catalogGeneration) { fetchArchiveChannels(session, repo) }
            fetched.onSuccess { channels ->
                persistReplayChannels(accountKey, channels, session, repo)
                val unchanged = disk != null &&
                    disk.value.map { it.streamId to it.name } == channels.map { it.streamId to it.name }
                if (!unchanged) showReplayChannels(channels, failed = false, keepSelection = disk != null)
            }
            if (fetched.isFailure && disk == null) showReplayChannels(emptyList(), failed = true, keepSelection = false)
        }
    }

    /** Streams get_live_streams keeping only channels with an archive, instead of holding the whole list. */
    private suspend fun fetchArchiveChannels(session: AuthSession, repo: AuthRepository): Result<List<XtreamChannel>> {
        val archivable = ArrayList<XtreamChannel>()
        return repo.streamCatalog(session, "get_live_streams", XtreamChannel.serializer()) { channel ->
            if (channel.tvArchive == 1 && channel.name.isNotBlank()) archivable += channel
            true
        }.map { archivable }
    }

    /**
     * One row per channel (qualities folded), by live category then name.
     * Rows with a favourite quality and "Continuer" (programs started and
     * not finished) come first.
     */
    private suspend fun showReplayChannels(archiveChannels: List<XtreamChannel>, failed: Boolean, keepSelection: Boolean) {
        val session = session ?: return
        val repo = authRepository ?: return
        // Rediffusion lists live channels, so Réglages' live filters
        // (hidden categories, adult lock, languages) apply through each
        // channel's live category as well as its own name.
        val liveCategories = CatalogCache.loadCategories(CatalogSection.LIVE, catalogGeneration) {
            repo.getLiveCategories(session)
        }.getOrElse { emptyList() }
        val filter = ReplayChannelFilter(
            liveCategories = liveCategories,
            hiddenCategoryIds = preferencesStore?.hiddenCategoryIds(CatalogSection.LIVE)?.first() ?: emptySet(),
            revealedAdultCategoryIds = preferencesStore?.revealedAdultCategoryIds?.first() ?: emptySet(),
            disabledPrefixes = preferencesStore?.disabledLanguagePrefixes?.first() ?: emptySet()
        )
        val visible = archiveChannels.filter(filter::isVisible)
        val groups = withContext(Dispatchers.Default) {
            groupReplayChannels(visible, liveCategories.associate { it.categoryId to it.categoryName })
        }
        replayChannelsById = visible.associateBy { it.streamId }
        replayGroupsById = groups.associateBy { it.representative.streamId }
        val favorites = _favoriteIds.value.filter { it.first == ContentType.LIVE.name }.mapTo(HashSet()) { it.second }
        val (favoriteGroups, otherGroups) = groups.partition { group -> group.variants.any { it.streamId in favorites } }
        fun ReplayChannelGroup.toCategory(quickAccess: Boolean) = BrowseCategory(
            id = representative.streamId,
            name = displayName,
            isQuickAccess = quickAccess,
            searchName = replaySearchKey(representative.name),
            subtitle = sidebarSubtitle()
        )
        val hasContinue = getPlaybackProgressUseCase?.getInProgress(ContentType.REPLAY.name)?.first()
            ?.any { replayChannelsById.containsKey(it.streamId.substringBeforeLast('_')) } == true
        val categories = buildList {
            if (hasContinue) add(BrowseCategory(CATEGORY_REPLAY_CONTINUE, "Continuer", isQuickAccess = true))
            favoriteGroups.forEach { add(it.toCategory(quickAccess = true)) }
            otherGroups.forEach { add(it.toCategory(quickAccess = false)) }
        }
        val previous = _uiState.value.selectedCategoryId
        val selected = previous.takeIf { id -> keepSelection && categories.any { it.id == id } }
        // Opening Rediffusion lands on the first real channel, not on "Continuer".
        val first = categories.firstOrNull { it.id != CATEGORY_REPLAY_CONTINUE } ?: categories.firstOrNull()
        _uiState.update {
            it.copy(
                categories = categories,
                selectedCategoryId = selected ?: first?.id,
                screenTitle = if (selected != null) it.screenTitle else screenTitleFor(ContentType.REPLAY),
                isLoading = false,
                contents = if (selected != null) it.contents else emptyList(),
                error = if (failed) "Impossible de charger les chaînes de rediffusion." else null,
                retryTarget = if (failed) BrowseRetryTarget.CATALOG else null
            )
        }
        if (selected == null) first?.let { loadReplayContentsForCategory(it.id) }
    }

    /**
     * The archive channel playing first, then its other qualities that keep
     * an archive too: the player falls back on them when this one's archive
     * won't serve (same program times, another stream id).
     */
    fun replayVariants(channelId: String?): List<ZapItem> {
        val session = session ?: return emptyList()
        val repo = authRepository ?: return emptyList()
        val channel = channelId?.let(replayChannelsById::get) ?: return emptyList()
        fun XtreamChannel.toZap() = ZapItem(streamId, name, streamIcon, repo.buildStreamUrl(session, streamId, "live"))
        val key = liveChannelKey(channel.name)
        val siblings = if (key.isEmpty()) emptyList()
            else replayChannelsById.values.filter { liveChannelKey(it.name) == key }.map { it.toZap() }
        return buildLiveFallbackChain(channel.toZap(), siblings)
    }

    /**
     * A channel's archive: memory, else disk, shown at once. The panel is
     * asked only for a copy older than CatalogCache's 15 minutes, and only
     * once the focus has rested on the channel - scrolling the sidebar
     * fires no request. A quality without a guide borrows a sibling's (same
     * programs, same times); a channel with none at all is offered hour by
     * hour. The neighbours are then warmed up one at a time.
     */
    private fun loadReplayContentsForCategory(channelId: String) {
        if (channelId == CATEGORY_REPLAY_CONTINUE) {
            loadReplayContinue()
            return
        }
        val group = replayGroupsById[channelId] ?: return
        contentLoadJob?.cancel()
        _uiState.update { it.copy(replayOnAir = null, replayHasGuide = true, replayIsContinue = false, replayArchiveDays = group.archiveDays) }
        contentLoadJob = viewModelScope.launch {
            val accountKey = com.btv.data.db.AccountScope.global.key.value
            val shown = peekOrSeedReplayGuide(accountKey, group)
            if (shown != null) {
                showReplayPrograms(group, shown.value, error = null)
            } else {
                _uiState.update { it.copy(isLoading = true, error = null, retryTarget = null) }
            }
            if (shown == null || !CatalogCache.isFullEpgFresh(shown.fetchedAtMs)) {
                delay(REPLAY_FOCUS_SETTLE_MS)
                val result = fetchReplayGuide(accountKey, group)
                val snapshot = result.getOrNull()
                val stale = "Rediffusion ancienne : actualisation impossible. Réessayer."
                when {
                    snapshot?.isStale == true -> showReplayPrograms(group, snapshot.value, error = stale)
                    snapshot != null -> if (snapshot.isFresh || shown == null) showReplayPrograms(group, snapshot.value, error = null)
                    shown != null -> showReplayPrograms(group, shown.value, error = stale)
                    else -> showReplayPrograms(group, emptyList(), error = "Impossible de charger la rediffusion de cette chaîne.")
                }
            }
            prefetchReplayNeighbours(accountKey, channelId)
        }
    }

    /** The first quality whose guide is already known (memory, else disk). */
    private suspend fun peekOrSeedReplayGuide(accountKey: String?, group: ReplayChannelGroup): EpgCacheSnapshot<List<XtreamEpgListing>>? {
        var empty: EpgCacheSnapshot<List<XtreamEpgListing>>? = null
        for (source in group.guideSources) {
            val known = peekOrSeedReplayEpg(accountKey, source.streamId) ?: continue
            if (known.value.isNotEmpty()) return known
            if (empty == null) empty = known
        }
        return empty
    }

    private suspend fun peekOrSeedReplayEpg(accountKey: String?, channelId: String): EpgCacheSnapshot<List<XtreamEpgListing>>? {
        CatalogCache.peekFullEpg(channelId)?.let { return it }
        val disk = replayDisk("programs read") { store -> accountKey?.let { store.readPrograms(it, channelId) } } ?: return null
        CatalogCache.seedFullEpg(channelId, disk.value, disk.fetchedAtMs, catalogGeneration)
        return EpgCacheSnapshot(disk.value, fetchedAtMs = disk.fetchedAtMs)
    }

    /**
     * The representative's guide; when the panel has none for it, the
     * other qualities' in turn. A failure on the representative is final:
     * the siblings sit on the same panel.
     */
    private suspend fun fetchReplayGuide(accountKey: String?, group: ReplayChannelGroup): Result<EpgCacheSnapshot<List<XtreamEpgListing>>> {
        var first: Result<EpgCacheSnapshot<List<XtreamEpgListing>>>? = null
        for (source in group.guideSources) {
            val result = fetchReplayEpg(accountKey, source)
            if (first == null) first = result
            if (result.isFailure) return first
            if (result.getOrNull()?.value?.isNotEmpty() == true) return result
        }
        return first ?: Result.failure(IllegalStateException("Aucune chaîne"))
    }

    /** One get_simple_data_table, trimmed to the archive window, written to disk when it's new. */
    private suspend fun fetchReplayEpg(accountKey: String?, channel: XtreamChannel): Result<EpgCacheSnapshot<List<XtreamEpgListing>>> {
        val session = session ?: return Result.failure(IllegalStateException("Pas de session"))
        val repo = authRepository ?: return Result.failure(IllegalStateException("Pas de session"))
        val result = CatalogCache.loadFullEpg(channel.streamId, catalogGeneration) {
            repo.getSimpleDataTable(session, channel.streamId).map { listings ->
                trimToArchiveWindow(listings, System.currentTimeMillis(), channel.tvArchiveDuration)
            }
        }
        result.getOrNull()?.takeIf { it.isFresh }?.let { fresh ->
            replayDisk("programs write") { store ->
                accountKey?.let { store.writePrograms(it, channel.streamId, fresh.value, fresh.fetchedAtMs) }
            }
        }
        return result
    }

    /**
     * The rows just below and above in the sidebar, one request at a time
     * after the current one has settled: the next D-pad step usually lands
     * on an archive already in memory. Cancelled with the selection.
     */
    private suspend fun prefetchReplayNeighbours(accountKey: String?, channelId: String) {
        val order = _uiState.value.categories.map { it.id }
        val index = order.indexOf(channelId)
        if (index < 0) return
        delay(REPLAY_PREFETCH_DELAY_MS)
        for (neighbourId in listOfNotNull(order.getOrNull(index + 1), order.getOrNull(index - 1))) {
            val neighbour = replayGroupsById[neighbourId] ?: continue
            val known = peekOrSeedReplayGuide(accountKey, neighbour)
            if (known != null && CatalogCache.isFullEpgFresh(known.fetchedAtMs)) continue
            fetchReplayGuide(accountKey, neighbour)
            delay(REPLAY_PREFETCH_DELAY_MS)
        }
    }

    /** A finished program of [channel] as a playable item (null if out of the archive or unaddressable). */
    private fun replayItem(
        channel: XtreamChannel,
        archiveDays: Int,
        ep: XtreamEpgListing,
        now: Long,
        channelLabel: String? = null
    ): ContentItem? {
        val session = session ?: return null
        val repo = authRepository ?: return null
        val startTs = (ep.startTimestamp.toLongOrNull() ?: return null) * 1000L
        val stopTs = (ep.stopTimestamp.toLongOrNull() ?: return null) * 1000L
        // Only programs that have actually finished, and are still within the archive window, are replayable.
        if (stopTs == 0L || stopTs >= now || (now - startTs) > archiveDays * 24 * 60 * 60 * 1000L) return null
        val durationMin = ((stopTs - startTs) / 60000L).toInt().coerceAtLeast(1)
        val url = ep.start?.let { repo.buildTimeshiftUrl(session, channel.streamId, it, durationMin) } ?: return null
        return ContentItem(
            id = "${channel.streamId}_${ep.startTimestamp}",
            name = decodeEpgText(ep.title).ifBlank { liveDisplayName(channel.name) },
            plot = decodeEpgText(ep.description),
            posterUrl = channel.streamIcon,
            backdropUrl = channel.streamIcon,
            badge = channelLabel,
            duration = formatReplayDuration(stopTs - startTs),
            streamUrl = url,
            epgStartTime = startTs,
            epgEndTime = stopTs
        )
    }

    private fun showReplayPrograms(group: ReplayChannelGroup, listings: List<XtreamEpgListing>, error: String?) {
        val session = session ?: return
        val repo = authRepository ?: return
        val channel = group.representative
        val channelId = channel.streamId
        val now = System.currentTimeMillis()
        listings.firstNotNullOfOrNull { panelOffsetMs(it.start, it.startTimestamp) }?.let { replayPanelOffsetMs = it }
        // Programs come from whichever quality had a guide; they always play on the representative.
        var items = listings.mapNotNull { replayItem(channel, group.archiveDays, it, now) }.sortedBy { it.epgStartTime }
        val onAir = listings.firstOrNull { ep ->
            val start = (ep.startTimestamp.toLongOrNull() ?: return@firstOrNull false) * 1000L
            val stop = (ep.stopTimestamp.toLongOrNull() ?: return@firstOrNull false) * 1000L
            start <= now && stop > now
        }?.let { ep ->
            ContentItem(
                id = REPLAY_ON_AIR_ID,
                name = decodeEpgText(ep.title).ifBlank { liveDisplayName(channel.name) },
                plot = decodeEpgText(ep.description),
                posterUrl = channel.streamIcon,
                backdropUrl = channel.streamIcon,
                epgStartTime = ep.startTimestamp.toLong() * 1000L,
                epgEndTime = ep.stopTimestamp.toLong() * 1000L
            ).takeIf { ep.start != null }?.also { replayOnAirPanelStart = ep.start }
        }
        // No guide at all: the archive is still there, hour by hour.
        val hasGuide = items.isNotEmpty() || onAir != null || error != null
        if (!hasGuide) {
            val offset = replayPanelOffsetMs ?: java.util.TimeZone.getDefault().getOffset(now).toLong()
            val slotFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
            items = hourlySlots(now, group.archiveDays, offset).mapNotNull { slot ->
                val url = repo.buildTimeshiftUrl(session, channelId, slot.panelStart, 60) ?: return@mapNotNull null
                ContentItem(
                    id = "${channelId}_${slot.startMs / 1000}",
                    name = "${slotFormat.format(Date(slot.startMs))} – ${slotFormat.format(Date(slot.endMs))}",
                    posterUrl = channel.streamIcon,
                    backdropUrl = channel.streamIcon,
                    duration = "1 h",
                    streamUrl = url,
                    epgStartTime = slot.startMs,
                    epgEndTime = slot.endMs
                )
            }.sortedBy { it.epgStartTime }
        }
        currentContentFullList = items
        _uiState.update { state ->
            if (state.selectedCategoryId != channelId) return@update state
            // A background refresh keeps the program the user is on; a first
            // display opens on the latest finished one.
            val keep = items.firstOrNull { it.id == state.selectedContentId } ?: items.lastOrNull()
            state.copy(
                contents = items,
                screenTitle = group.displayName,
                selectedContentId = keep?.id,
                selectedContent = keep,
                replayOnAir = onAir,
                replayHasGuide = hasGuide,
                replayArchiveDays = group.archiveDays,
                replayIsContinue = false,
                isLoading = false,
                error = error,
                retryTarget = if (error != null) BrowseRetryTarget.CONTENT else null
            )
        }
    }

    /**
     * Programs started and not finished, every channel together, most
     * recently watched first. Their guide entry comes from memory or disk
     * (the archive cache keeps 15 days); one that left the archive is gone.
     */
    private fun loadReplayContinue() {
        contentLoadJob?.cancel()
        contentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, replayOnAir = null, replayHasGuide = true, replayIsContinue = true, replayArchiveDays = null) }
            val accountKey = com.btv.data.db.AccountScope.global.key.value
            val now = System.currentTimeMillis()
            val inProgress = getPlaybackProgressUseCase?.getInProgress(ContentType.REPLAY.name)?.first().orEmpty()
                .sortedByDescending { it.lastProgressedAt }
            val items = inProgress.mapNotNull { progress ->
                val channelId = progress.streamId.substringBeforeLast('_')
                val startTs = progress.streamId.substringAfterLast('_')
                val channel = replayChannelsById[channelId] ?: return@mapNotNull null
                val group = replayGroupsById.values.firstOrNull { g -> g.variants.any { it.streamId == channelId } }
                val listing = group?.guideSources.orEmpty().ifEmpty { listOf(channel) }.firstNotNullOfOrNull { source ->
                    peekOrSeedReplayEpg(accountKey, source.streamId)?.value?.firstOrNull { it.startTimestamp == startTs }
                } ?: return@mapNotNull null
                replayItem(channel, group?.archiveDays ?: (channel.tvArchiveDuration ?: 1), listing, now,
                    channelLabel = group?.displayName ?: liveDisplayName(channel.name))
            }
            currentContentFullList = items
            _uiState.update { state ->
                if (state.selectedCategoryId != CATEGORY_REPLAY_CONTINUE) return@update state
                state.copy(
                    contents = items,
                    screenTitle = "Continuer",
                    selectedContentId = items.firstOrNull()?.id,
                    selectedContent = items.firstOrNull(),
                    isLoading = false,
                    error = null,
                    retryTarget = null
                )
            }
        }
    }

    /**
     * "Reprendre depuis le début" on what the channel is airing: the archive
     * from the program's start up to now (the panel has nothing later yet).
     */
    fun startOverReplay() {
        val session = session ?: return
        val repo = authRepository ?: return
        val onAir = _uiState.value.replayOnAir ?: return
        val channelId = _uiState.value.selectedCategoryId ?: return
        val start = onAir.epgStartTime ?: return
        val panelStart = replayOnAirPanelStart ?: return
        val minutes = ((System.currentTimeMillis() - start) / 60_000L).toInt().coerceAtLeast(1)
        val url = repo.buildTimeshiftUrl(session, channelId, panelStart, minutes) ?: return
        launchPlayable(onAir.copy(id = "${channelId}_${start / 1000}", streamUrl = url))
    }

    /** Called every minute while Rediffusion is on screen: a program that just ended joins the list. */
    fun onReplayMinuteTick() {
        val state = _uiState.value
        if (state.contentType != ContentType.REPLAY || state.replayIsContinue || state.isLoading) return
        val end = state.replayOnAir?.epgEndTime ?: return
        if (end > System.currentTimeMillis()) return
        val group = state.selectedCategoryId?.let(replayGroupsById::get) ?: return
        val listings = group.guideSources.firstNotNullOfOrNull { source ->
            CatalogCache.peekFullEpg(source.streamId)?.value?.takeIf { it.isNotEmpty() }
        } ?: return
        showReplayPrograms(group, listings, state.error)
    }

    private fun persistReplayChannels(accountKey: String?, channels: List<XtreamChannel>, session: AuthSession, repo: AuthRepository) {
        accountKey ?: return
        viewModelScope.launch {
            // Category names travel with the channels, so the adult filter
            // still works from disk when the category list is unreachable.
            val names = CatalogCache.loadCategories(CatalogSection.LIVE, catalogGeneration) { repo.getLiveCategories(session) }
                .getOrElse { emptyList() }.associate { it.categoryId to it.categoryName }
            val named = channels.map { c -> if (c.categoryName != null) c else c.copy(categoryName = c.categoryId?.let(names::get)) }
            replayDisk("channels write") { store ->
                store.writeChannels(accountKey, named, System.currentTimeMillis(), listOf(session.username, session.password))
            }
        }
    }

    private suspend fun pruneReplayArchiveOnce() {
        if (replayPruned) return
        replayPruned = true
        replayDisk("prune") { store -> store.prune(System.currentTimeMillis()) }
    }

    /** Disk is a shortcut, never a requirement: a failing read or write is logged and skipped. */
    private suspend fun <T> replayDisk(what: String, block: suspend (com.btv.data.repository.ReplayArchiveStore) -> T?): T? {
        val store = replayArchiveStore ?: return null
        return try {
            block(store)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("BtvReplay", "Disk $what failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /**
     * Per-category content, backed by [CatalogCache] - a category's items
     * essentially never change within a session, and re-fetching them from
     * the network every single time that category (or "Tout afficher",
     * which aggregates every one of them) is revisited is what made repeat
     * visits sit on "Chargement..." exactly as long as the very first visit
     * did, network round-trip and all.
     */
    // Keep failures as Result values so an unavailable category is never
    // mistaken for a real empty list, including inside aggregate views.
    private suspend fun fetchVodStreamsResult(session: AuthSession, repo: AuthRepository, categoryId: String): Result<List<XtreamVod>> =
        CatalogCache.loadVodStreams(categoryId, catalogGeneration) { repo.getVodStreams(session, categoryId) }

    private suspend fun fetchSeriesResult(session: AuthSession, repo: AuthRepository, categoryId: String): Result<List<XtreamSeries>> =
        CatalogCache.loadSeries(categoryId, catalogGeneration) { repo.getSeries(session, categoryId) }

    private suspend fun fetchLiveStreamsResult(session: AuthSession, repo: AuthRepository, categoryId: String): Result<List<XtreamChannel>> =
        CatalogCache.loadLiveStreams(categoryId, catalogGeneration) { repo.getLiveStreams(session, categoryId) }

    private fun loadRealContentsForCategory(type: ContentType, categoryId: String) {
        val session = session ?: return
        val repo = authRepository ?: return

        // Switching category invalidates any enrichment loop AND any content
        // load still running for the previous one - same guard as Tizen's
        // channelListEnrichToken, extended to every loader below (see
        // contentLoadJob doc comment).
        epgEnrichJob?.cancel()
        contentLoadJob?.cancel()

        when (categoryId) {
            CATEGORY_CONTINUE_WATCHING -> { loadContinueWatchingContent(type); return }
            CATEGORY_FAVORITES -> { loadFavoritesContent(type); return }
            CATEGORY_RECENTLY_VIEWED -> { loadRecentlyViewedContent(type); return }
            CATEGORY_SHOW_ALL -> { loadShowAllContent(type); return }
            CATEGORY_RECENTLY_ADDED -> { loadRecentlyAddedContent(type); return }
        }

        contentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val itemsResult: Result<List<ContentItem>> = when (type) {
                // Réglages' language filter is applied at the category-name
                // level for every section (loadRealCatalog), but a Live
                // category isn't always itself language-tagged even when
                // the individual channels inside it are (e.g. a generic
                // "SPORTS" category mixing |FR| and |AR| channels) - so Live
                // needs the same check again per channel name here.
                ContentType.LIVE -> {
                    val disabledPrefixes = preferencesStore?.disabledLanguagePrefixes?.first() ?: emptySet()
                    fetchLiveStreamsResult(session, repo, categoryId).map { streams -> streams
                        .filter { it.name.isNotBlank() }
                        .filterNot { extractLanguagePrefix(it.name) in disabledPrefixes }
                        .sortedBy { it.name }
                        .map { it.toContentItem() }
                    }
                }
                ContentType.VOD -> fetchVodStreamsResult(session, repo, categoryId).map { streams -> streams
                    .filter { it.name.isNotBlank() }
                    .sortedBy { it.name }
                    .map { it.toContentItem() }
                }
                ContentType.SERIES -> fetchSeriesResult(session, repo, categoryId).map { series -> series
                    .filter { it.title.isNotBlank() }
                    .sortedBy { it.title }
                    .map { it.toContentItem() }
                }
                else -> Result.success(emptyList())
            }
            val items = itemsResult.getOrElse { emptyList() }

            currentContentFullList = items
            _uiState.update { state ->
                state.copy(
                    contents = items,
                    selectedContentId = items.firstOrNull()?.id,
                    selectedContent = items.firstOrNull(),
                    isLoading = false,
                    error = if (itemsResult.isFailure) "Impossible de charger cette catégorie." else state.error,
                    retryTarget = if (itemsResult.isFailure) BrowseRetryTarget.CONTENT else state.retryTarget
                )
            }
        }
    }

    /**
     * Joins PlaybackProgressEntity (position/duration, keyed by the real
     * content id - see PlayerViewModel's contentId doc comment) with History
     * (name/poster, since progress rows don't carry display metadata) to
     * build "Continuer à regarder", ordered most-recently-played first.
     * Mirrors Tizen's getContinueWatchingList (js/data.js).
     */
    private fun loadContinueWatchingContent(type: ContentType) {
        contentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val inProgress = getPlaybackProgressUseCase?.getInProgress(type.name)?.first().orEmpty()
            val historyById = when (type) {
                ContentType.VOD -> getRecentlyWatchedUseCase?.getRecentlyWatched(200)
                ContentType.SERIES -> getRecentlyWatchedUseCase?.getRecentlyWatchedSeries(200)
                ContentType.LIVE -> getRecentlyWatchedUseCase?.getRecentlyWatchedLive(200)
                else -> null
            }?.first().orEmpty().associateBy { it.streamId }

            val items = inProgress.mapNotNull { progress ->
                val history = historyById[progress.streamId] ?: return@mapNotNull null
                ContentItem(
                    id = progress.streamId,
                    name = history.name,
                    posterUrl = history.posterUrl,
                    backdropUrl = history.posterUrl,
                    playbackProgress = (progress.progressPercent / 100f).coerceIn(0f, 1f),
                    streamUrl = buildStreamUrlFor(progress.streamId, type, progress.containerExtension ?: history.containerExtension),
                    seriesId = history.seriesId,
                    seasonNum = history.seasonNumber
                )
            }
            currentContentFullList = items
            _uiState.update { state ->
                state.copy(contents = items, selectedContentId = items.firstOrNull()?.id, selectedContent = items.firstOrNull(), isLoading = false)
            }
        }
    }

    /**
     * Home's "Favoris" tile (contentType == FAVORITES) - port of Tizen's
     * renderSidebarCategories for browseSectionKey === 'favorites'
     * (js/browse.js:64-74): the sidebar is 3 real sections (Films/Séries/En
     * Direct) with real counts, each showing that type's actual favorited
     * items - not the single fabricated mock list this fell back to before
     * (isRealDataCapable excludes FAVORITES, so setContentType(FAVORITES)
     * used to land in loadMockData unconditionally).
     */
    private fun loadFavoritesCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val favorites = getFavoritesUseCase?.getAllFavorites()?.first().orEmpty()
            val counts = favorites.groupingBy { it.type }.eachCount()
            val categories = listOf(
                BrowseCategory("fav_movies", "Films", counts[ContentType.VOD.name] ?: 0, ContentType.VOD),
                BrowseCategory("fav_series", "Séries", counts[ContentType.SERIES.name] ?: 0, ContentType.SERIES),
                BrowseCategory("fav_live", "En Direct", counts[ContentType.LIVE.name] ?: 0, ContentType.LIVE)
            )
            _uiState.update {
                it.copy(
                    categories = categories,
                    selectedCategoryId = categories.firstOrNull()?.id,
                    screenTitle = "Favoris",
                    isLoading = false
                )
            }
            categories.firstOrNull()?.let { loadFavoritesContent(it.type) }
        }
    }

    private fun loadFavoritesContent(type: ContentType) {
        contentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val favorites = getFavoritesUseCase?.getFavoritesByType(type.name)?.first().orEmpty()
            val items = favorites.map { fav ->
                ContentItem(
                    id = fav.streamId,
                    name = fav.name,
                    posterUrl = fav.posterUrl,
                    backdropUrl = fav.posterUrl,
                    // A favorited series is its own card (js/browse.js:
                    // "ni les saisons ni les episodes ne sont favorisables
                    // individuellement") - it has no stream of its own,
                    // opening it must drill into seasons like any other.
                    streamUrl = if (type == ContentType.SERIES) null else buildStreamUrlFor(fav.streamId, type, fav.containerExtension),
                    contentKind = if (type == ContentType.SERIES) ContentKind.SERIES else ContentKind.PLAYABLE
                )
            }
            currentContentFullList = items
            _uiState.update { state ->
                state.copy(contents = items, selectedContentId = items.firstOrNull()?.id, selectedContent = items.firstOrNull(), isLoading = false)
            }
        }
    }

    private fun loadRecentlyViewedContent(type: ContentType) {
        contentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val history = when (type) {
                ContentType.VOD -> getRecentlyWatchedUseCase?.getRecentlyWatched(RECENTLY_VIEWED_CAP)
                ContentType.SERIES -> getRecentlyWatchedUseCase?.getRecentlyWatchedSeries(RECENTLY_VIEWED_CAP)
                ContentType.LIVE -> getRecentlyWatchedUseCase?.getRecentlyWatchedLive(RECENTLY_VIEWED_CAP)
                else -> null
            }?.first().orEmpty()
            val items = if (type == ContentType.SERIES) groupSeriesHistory(history) else history.map { h ->
                ContentItem(
                    id = h.streamId,
                    name = h.name,
                    posterUrl = h.posterUrl,
                    backdropUrl = h.posterUrl,
                    // History always records an actually-played movie or
                    // channel - see loadContinueWatchingContent.
                    streamUrl = buildStreamUrlFor(h.streamId, type, h.containerExtension)
                )
            }
            currentContentFullList = items
            _uiState.update { state ->
                state.copy(contents = items, selectedContentId = items.firstOrNull()?.id, selectedContent = items.firstOrNull(), isLoading = false)
            }
        }
    }

    /**
     * Port of Tizen's groupRecentList (js/utils.js): episodes of one series
     * collapse into a single series card, represented by the most recently
     * watched episode (history is newest first). Opening it drills into the
     * seasons like any series card.
     */
    private fun groupSeriesHistory(history: List<com.btv.data.db.entities.HistoryEntity>): List<ContentItem> {
        val groups = LinkedHashMap<String, MutableList<com.btv.data.db.entities.HistoryEntity>>()
        val ungrouped = ArrayList<Pair<Int, ContentItem>>()
        history.forEachIndexed { index, h ->
            val seriesId = h.seriesId
            if (seriesId.isNullOrEmpty()) {
                ungrouped += index to ContentItem(
                    id = h.streamId, name = h.name, posterUrl = h.posterUrl, backdropUrl = h.posterUrl,
                    streamUrl = buildStreamUrlFor(h.streamId, ContentType.SERIES, h.containerExtension)
                )
            } else groups.getOrPut(seriesId) { ArrayList() } += h
        }
        val grouped = groups.map { (seriesId, episodes) ->
            val latest = episodes.first()
            history.indexOf(latest) to ContentItem(
                id = seriesId,
                name = latest.categoryName.ifBlank { latest.name },
                posterUrl = latest.posterUrl,
                backdropUrl = latest.posterUrl,
                badge = "${episodes.size} ép.",
                contentKind = ContentKind.SERIES
            )
        }
        return (grouped + ungrouped).sortedBy { it.first }.map { it.second }
    }

    /** Tizen removeFocusedFromRecent: removes the entry, or every episode of a grouped series card. */
    fun removeSelectedFromHistory() {
        val state = _uiState.value
        if (!state.canRemoveFromHistory) return
        val item = state.selectedContent ?: return
        val useCase = getRecentlyWatchedUseCase ?: return
        val type = state.mediaType
        viewModelScope.launch {
            try {
                if (type == ContentType.SERIES && item.contentKind == ContentKind.SERIES) {
                    useCase.removeSeriesFromHistory(item.id, type.name)
                } else {
                    useCase.removeFromHistory(item.id, type.name)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("BtvBrowse", "History removal failed: ${error.javaClass.simpleName}")
                return@launch
            }
            if (_uiState.value.selectedCategoryId != CATEGORY_RECENTLY_VIEWED) return@launch
            // Keep the selection where it was: the next entry takes the removed one's place.
            val before = _uiState.value.contents
            val index = before.indexOfFirst { it.id == item.id }
            val remaining = before.filterNot { it.id == item.id }
            val next = remaining.getOrNull(index.coerceAtMost(remaining.size - 1).coerceAtLeast(0))
            currentContentFullList = remaining
            _uiState.update { it.copy(contents = remaining, selectedContentId = next?.id, selectedContent = next) }
            _messages.tryEmit("Retiré de l'historique")
        }
    }

    /** Builds a compact search index and at most 300 full cards from the
     * unfiltered stream. If that endpoint fails, scans categories in bounded
     * batches. Decoding the whole VOD response previously caused an OOM. */
    private fun loadShowAllContent(type: ContentType) {
        val session = session ?: return
        val repo = authRepository ?: return
        contentLoadJob = viewModelScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val realCategories = _uiState.value.categories.filterNot { it.isQuickAccess }
            val disabledPrefixes = if (type == ContentType.LIVE) {
                preferencesStore?.disabledLanguagePrefixes?.first() ?: emptySet()
            } else emptySet()
            val lockedCategories = if (type == ContentType.LIVE) lockedLiveCategoryIds else emptySet()
            // The snapshot is only valid for the same filters: locked adult
            // categories are part of its key.
            val snapshotFilter = disabledPrefixes + lockedCategories.map { "#locked:$it" }
            val cached = showAllSnapshotStore?.read(session, type, snapshotFilter)
            currentCoroutineContext().ensureActive()
            if (cached != null && _uiState.value.selectedCategoryId == CATEGORY_SHOW_ALL &&
                _uiState.value.contentType == type) {
                val cards = cached.preview.map { it.toContentItem(type, session, repo) }
                showAllIndex = cached.refs
                showAllInitialItems = cards
                showAllScanFailures = 0
                showAllUnfiltered = true
                currentContentFullList = cards
                _uiState.update { state -> state.copy(
                    contents = cards,
                    selectedContentId = cards.firstOrNull()?.id,
                    selectedContent = cards.firstOrNull(),
                    isLoading = false,
                    loadingProgress = null
                ) }
                android.util.Log.i("BrowseShowAll", "Persisted catalog restored ${cached.refs.size} titles in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
                return@launch
            }
            val index = ArrayList<ShowAllRef>()
            val preview = ArrayList<ContentItem>(SHOW_ALL_CAP)
            var failures = 0
            val failuresByKind = HashMap<String, Int>()
            var processed = 0
            var rateLimited = false
            var retryWaitMs = 0L

            suspend fun retryAfterRateLimit(categoryId: String, first: Result<ShowAllIndexedCategory>): Result<ShowAllIndexedCategory> {
                var result = first
                var attempt = 0
                while ((result.exceptionOrNull() as? retrofit2.HttpException)?.code() == 429 &&
                    attempt < 2 && retryWaitMs < 90_000L) {
                    rateLimited = true
                    val headerSeconds = (result.exceptionOrNull() as? retrofit2.HttpException)
                        ?.response()?.headers()?.get("Retry-After")?.toLongOrNull()
                    val waitMs = (headerSeconds?.times(1_000L) ?: (10_000L * (attempt + 1)))
                        .coerceIn(1_000L, 30_000L)
                    if (retryWaitMs + waitMs > 90_000L) break
                    retryWaitMs += waitMs
                    _uiState.update { it.copy(loadingProgress = "Serveur occupé : nouvelle tentative…") }
                    delay(waitMs)
                    result = indexShowAllCategory(type, session, repo, categoryId, disabledPrefixes)
                    attempt++
                }
                return result
            }
            fun publishPreview(progress: String) {
                showAllInitialItems = preview.toList()
                currentContentFullList = showAllInitialItems
                _uiState.update { state ->
                    if (state.selectedCategoryId != CATEGORY_SHOW_ALL) state else {
                        val selected = showAllInitialItems.firstOrNull { it.id == state.selectedContentId }
                            ?: showAllInitialItems.firstOrNull()
                        state.copy(
                            contents = if (state.contentSearch.isBlank()) showAllInitialItems else state.contents,
                            selectedContentId = if (state.contentSearch.isBlank()) selected?.id else state.selectedContentId,
                            selectedContent = if (state.contentSearch.isBlank()) selected else state.selectedContent,
                            loadingProgress = progress
                        )
                    }
                }
            }

            _uiState.update { it.copy(isLoading = true, loadingProgress = "Catalogue : chargement…") }
            val unfilteredResult = streamShowAllCatalog(type, session, repo, disabledPrefixes, lockedCategories) { ref, card ->
                index.add(ref)
                if (preview.size < SHOW_ALL_CAP) preview.add(card())
                if (index.size % SHOW_ALL_PROGRESS_STEP == 0) {
                    _uiState.update { it.copy(loadingProgress = "Catalogue : ${index.size} titres") }
                }
                true
            }
            currentCoroutineContext().ensureActive()
            // Panels may refuse the unfiltered list; an empty list despite
            // known categories is treated as a refusal as well.
            val streamed = unfilteredResult.isSuccess && (index.isNotEmpty() || realCategories.isEmpty())
            if (streamed) {
                publishPreview("Catalogue : ${index.size} titres")
            } else {
                val error = unfilteredResult.exceptionOrNull()
                rateLimited = (error as? retrofit2.HttpException)?.code() == 429
                val kind = if (error is retrofit2.HttpException) "HTTP ${error.code()}"
                    else error?.javaClass?.simpleName ?: "empty"
                android.util.Log.w("BrowseShowAll", "Unfiltered catalog unavailable ($kind), scanning categories")
                index.clear()
                preview.clear()
                _uiState.update { it.copy(loadingProgress = "Catalogue : 0/${realCategories.size} catégories") }
            }

            // Await only three raw category responses at a time. Once a batch
            // is indexed, its raw lists can be released before the next one.
            if (!streamed) for (batch in realCategories.chunked(SHOW_ALL_CONCURRENCY)) {
                val initialResults = if (rateLimited) {
                    batch.map { cat ->
                        delay(2_000L)
                        indexShowAllCategory(type, session, repo, cat.id, disabledPrefixes)
                    }
                } else coroutineScope {
                    batch.map { cat -> async { indexShowAllCategory(type, session, repo, cat.id, disabledPrefixes) } }.awaitAll()
                }
                val results = initialResults.mapIndexed { indexInBatch, result ->
                    retryAfterRateLimit(batch[indexInBatch].id, result)
                }
                results.forEach { result ->
                    val category = result.getOrNull()
                    if (category == null) {
                        failures++
                        val error = result.exceptionOrNull()
                        val kind = if (error is retrofit2.HttpException) "HTTP ${error.code()}"
                            else error?.javaClass?.simpleName ?: "Unknown"
                        failuresByKind[kind] = (failuresByKind[kind] ?: 0) + 1
                    } else {
                        index.addAll(category.refs)
                        val remaining = SHOW_ALL_CAP - preview.size
                        if (remaining > 0) preview.addAll(category.preview.take(remaining))
                    }
                }
                processed += batch.size
                publishPreview("Catalogue : $processed/${realCategories.size} catégories")
            }
            showAllIndex = index
            showAllScanFailures = failures
            showAllUnfiltered = streamed
            if (streamed) {
                android.util.Log.i("BrowseShowAll", "Unfiltered catalog indexed ${index.size} titles in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            }
            if (failures > 0) {
                android.util.Log.w("BrowseShowAll", "${failures}/${realCategories.size} category failures: $failuresByKind")
            }
            _uiState.update { state ->
                val selected = showAllInitialItems.firstOrNull { it.id == state.selectedContentId }
                    ?: showAllInitialItems.firstOrNull()
                state.copy(
                    contents = if (state.contentSearch.isBlank()) showAllInitialItems else emptyList(),
                    selectedContentId = if (state.contentSearch.isBlank()) selected?.id else null,
                    selectedContent = if (state.contentSearch.isBlank()) selected else null,
                    isLoading = false,
                    loadingProgress = null,
                    error = if (failures > 0) aggregateLoadError(failures, index.isNotEmpty()) else state.error,
                    retryTarget = if (failures > 0) BrowseRetryTarget.CONTENT else state.retryTarget
                )
            }
            _uiState.value.contentSearch.takeIf { it.isNotBlank() }?.let(::updateContentSearch)
            if (streamed) {
                try {
                    showAllSnapshotStore?.write(session, type, snapshotFilter, index, preview)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("BrowseShowAll", "Catalog snapshot unavailable: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun CachedShowAllCard.toContentItem(
        type: ContentType, session: AuthSession, repo: AuthRepository
    ): ContentItem = ContentItem(
        id = id,
        name = name,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        plot = plot,
        cast = cast,
        rating = rating,
        year = year,
        duration = duration,
        genre = genre,
        streamUrl = when (type) {
            ContentType.VOD -> repo.buildStreamUrl(
                session, id, "movie", resolveExtension(extension, session.userInfo.allowedOutputFormats, "mp4")
            )
            ContentType.LIVE -> repo.buildStreamUrl(session, id, "live", extension)
            else -> null
        },
        contentKind = if (type == ContentType.SERIES) ContentKind.SERIES else ContentKind.PLAYABLE
    )

    /** Reads the unfiltered list for [type] one element at a time; [onEntry]
     * gets the compact ref and a lazy full card, and returns false to stop. */
    private suspend fun streamShowAllCatalog(
        type: ContentType, session: AuthSession, repo: AuthRepository,
        disabledPrefixes: Set<String>, lockedCategories: Set<String>,
        onEntry: (ShowAllRef, () -> ContentItem) -> Boolean
    ): Result<Int> = when (type) {
        ContentType.LIVE -> repo.streamCatalog(session, "get_live_streams", XtreamChannel.serializer()) { channel ->
            if (extractLanguagePrefix(channel.name) in disabledPrefixes || channel.categoryId in lockedCategories) true
            else onEntry(ShowAllRef(channel.categoryId.orEmpty(), channel.streamId, channel.name)) { channel.toContentItem() }
        }
        ContentType.VOD -> repo.streamCatalog(session, "get_vod_streams", XtreamVod.serializer()) { vod ->
            onEntry(ShowAllRef(vod.categoryId.orEmpty(), vod.streamId, vod.name)) { vod.toContentItem() }
        }
        ContentType.SERIES -> repo.streamCatalog(session, "get_series", XtreamSeries.serializer()) { series ->
            onEntry(ShowAllRef(series.categoryId.orEmpty(), series.seriesId, series.title)) { series.toContentItem() }
        }
        else -> Result.success(0)
    }

    private suspend fun indexShowAllCategory(
        type: ContentType, session: AuthSession, repo: AuthRepository,
        categoryId: String, disabledPrefixes: Set<String>
    ): Result<ShowAllIndexedCategory> = when (type) {
        ContentType.LIVE -> fetchLiveStreamsResult(session, repo, categoryId).let { fetched ->
            withContext(Dispatchers.Default) { fetched.map { streams ->
                val refs = ArrayList<ShowAllRef>(streams.size)
                val preview = ArrayList<ContentItem>(minOf(SHOW_ALL_CAP, streams.size))
                streams.forEach { channel ->
                    if (extractLanguagePrefix(channel.name) !in disabledPrefixes) {
                        refs.add(ShowAllRef(categoryId, channel.streamId, channel.name))
                        if (preview.size < SHOW_ALL_CAP) preview.add(channel.toContentItem())
                    }
                }
                ShowAllIndexedCategory(refs, preview)
            } }
        }
        ContentType.VOD -> fetchVodStreamsResult(session, repo, categoryId).let { fetched ->
            withContext(Dispatchers.Default) { fetched.map { streams ->
                val refs = ArrayList<ShowAllRef>(streams.size)
                val preview = ArrayList<ContentItem>(minOf(SHOW_ALL_CAP, streams.size))
                streams.forEach { vod ->
                    refs.add(ShowAllRef(categoryId, vod.streamId, vod.name))
                    if (preview.size < SHOW_ALL_CAP) preview.add(vod.toContentItem())
                }
                ShowAllIndexedCategory(refs, preview)
            } }
        }
        ContentType.SERIES -> fetchSeriesResult(session, repo, categoryId).let { fetched ->
            withContext(Dispatchers.Default) { fetched.map { series ->
                val refs = ArrayList<ShowAllRef>(series.size)
                val preview = ArrayList<ContentItem>(minOf(SHOW_ALL_CAP, series.size))
                series.forEach { item ->
                    refs.add(ShowAllRef(categoryId, item.seriesId, item.title))
                    if (preview.size < SHOW_ALL_CAP) preview.add(item.toContentItem())
                }
                ShowAllIndexedCategory(refs, preview)
            } }
        }
        else -> Result.success(ShowAllIndexedCategory(emptyList(), emptyList()))
    }

    private suspend fun resolveShowAllSearch(query: String, index: List<ShowAllRef>, type: ContentType) {
        val session = session ?: return
        val repo = authRepository ?: return
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val refs = withContext(Dispatchers.Default) {
            // The index already excludes locked adult categories; the check
            // also covers a snapshot built before a category was re-locked.
            index.asSequence().filter { it.name.contains(query, ignoreCase = true) &&
                (type != ContentType.LIVE || it.categoryId !in lockedLiveCategoryIds) }
                .take(SHOW_ALL_CAP).toList()
        }
        if (showAllUnfiltered) {
            // One streamed pass rebuilds only the matching cards and stops as
            // soon as all of them are found.
            val wanted = refs.mapTo(HashSet()) { it.id }
            val found = HashMap<String, ContentItem>(refs.size)
            val pass = streamShowAllCatalog(type, session, repo, emptySet(), emptySet()) { ref, card ->
                if (ref.id in wanted && ref.id !in found) found[ref.id] = card()
                found.size < wanted.size
            }
            currentCoroutineContext().ensureActive()
            if (_uiState.value.selectedCategoryId != CATEGORY_SHOW_ALL || _uiState.value.contentSearch != query ||
                _uiState.value.contentType != type) return
            val matches = refs.mapNotNull { found[it.id] }
            if (pass.isSuccess) {
                val firstIndex = refs.firstOrNull()?.let(index::indexOf) ?: -1
                android.util.Log.i("BrowseShowAll", "Unfiltered search resolved ${matches.size} cards; first index $firstIndex in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            }
            currentContentFullList = matches
            _uiState.update { state -> state.copy(
                contents = matches,
                selectedContentId = matches.firstOrNull()?.id,
                selectedContent = matches.firstOrNull(),
                isLoading = false,
                loadingProgress = null,
                error = if (pass.isFailure) "Résultats indisponibles. Réessayer." else null,
                retryTarget = if (pass.isFailure) BrowseRetryTarget.CONTENT else null
            ) }
            return
        }
        val resolved = HashMap<ShowAllRef, ContentItem>(refs.size)
        var failures = showAllScanFailures
        // Each category is fetched and released before the next one. Only
        // matching cards are converted, so a broad provider response cannot
        // rebuild the old full-catalog ContentItem list in memory.
        for ((categoryId, categoryRefs) in refs.groupBy { it.categoryId }) {
            currentCoroutineContext().ensureActive()
            val ids = categoryRefs.mapTo(HashSet()) { it.id }
            val items: Result<Map<String, ContentItem>> = when (type) {
                ContentType.LIVE -> fetchLiveStreamsResult(session, repo, categoryId).let { fetched ->
                    withContext(Dispatchers.Default) { fetched.map { streams ->
                        streams.asSequence().filter { it.streamId in ids }
                            .associate { it.streamId to it.toContentItem() }
                    } }
                }
                ContentType.VOD -> fetchVodStreamsResult(session, repo, categoryId).let { fetched ->
                    withContext(Dispatchers.Default) { fetched.map { streams ->
                        streams.asSequence().filter { it.streamId in ids }
                            .associate { it.streamId to it.toContentItem() }
                    } }
                }
                ContentType.SERIES -> fetchSeriesResult(session, repo, categoryId).let { fetched ->
                    withContext(Dispatchers.Default) { fetched.map { series ->
                        series.asSequence().filter { it.seriesId in ids }
                            .associate { it.seriesId to it.toContentItem() }
                    } }
                }
                else -> Result.success(emptyMap())
            }
            currentCoroutineContext().ensureActive()
            if (items.isFailure) failures++ else {
                val byId = items.getOrThrow()
                categoryRefs.forEach { ref -> byId[ref.id]?.let { resolved[ref] = it } }
            }
        }
        if (_uiState.value.selectedCategoryId != CATEGORY_SHOW_ALL || _uiState.value.contentSearch != query ||
            _uiState.value.contentType != type) return
        val matches = refs.mapNotNull(resolved::get)
        currentContentFullList = matches
        _uiState.update { state -> state.copy(
            contents = matches,
            selectedContentId = matches.firstOrNull()?.id,
            selectedContent = matches.firstOrNull(),
            isLoading = false,
            loadingProgress = null,
            error = if (failures > 0) aggregateLoadError(failures, matches.isNotEmpty())
                else if (state.retryTarget == BrowseRetryTarget.CATALOG) state.error else null,
            retryTarget = if (failures > 0) BrowseRetryTarget.CONTENT
                else if (state.retryTarget == BrowseRetryTarget.CATALOG) state.retryTarget else null
        ) }
    }

    /**
     * Exact port of Tizen's computeRecentlyAdded (js/data.js): first 12
     * categories (sidebar order), items concatenated and sorted by
     * added/last_modified desc, capped at 40, badged "NOUVEAU".
     */
    private fun loadRecentlyAddedContent(type: ContentType) {
        val session = session ?: return
        val repo = authRepository ?: return
        contentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val realCategories = _uiState.value.categories.filterNot { it.isQuickAccess }.take(RECENTLY_ADDED_CATEGORY_SCAN)
            // Same fix as "Tout afficher" (loadShowAllContent): cached
            // per-category fetch instead of a network call every visit, and
            // bounded-concurrent instead of one-at-a-time for a cache miss.
            val semaphore = Semaphore(SHOW_ALL_CONCURRENCY)
            val results: List<Result<List<Pair<ContentItem, Long>>>> = coroutineScope {
                realCategories.map { cat ->
                    async {
                        semaphore.withPermit {
                            when (type) {
                                ContentType.VOD -> fetchVodStreamsResult(session, repo, cat.id).map { streams -> streams
                                    .map { it.toContentItem() to (it.added?.toLongOrNull() ?: 0L) }
                                }
                                ContentType.SERIES -> fetchSeriesResult(session, repo, cat.id).map { series -> series
                                    .map { it.toContentItem() to (it.lastModified?.toLongOrNull() ?: 0L) }
                                }
                                else -> Result.success(emptyList())
                            }
                        }
                    }
                }.awaitAll()
            }
            val failures = results.count { it.isFailure }
            val aggregated = results.flatMap { it.getOrElse { emptyList() } }
            val top = aggregated.sortedByDescending { it.second }.take(RECENTLY_ADDED_CAP).map { it.first.copy(badge = "NOUVEAU") }
            currentContentFullList = top
            _uiState.update { state ->
                state.copy(
                    contents = top, selectedContentId = top.firstOrNull()?.id,
                    selectedContent = top.firstOrNull(), isLoading = false,
                    error = if (failures > 0) aggregateLoadError(failures, aggregated.isNotEmpty()) else state.error,
                    retryTarget = if (failures > 0) BrowseRetryTarget.CONTENT else state.retryTarget
                )
            }
        }
    }

    private fun aggregateLoadError(failures: Int, hasItems: Boolean): String =
        if (hasItems) "$failures catégorie(s) indisponible(s). Liste partielle : réessayer."
        else "Impossible de charger $failures catégorie(s). Réessayer."

    /**
     * Port of Tizen's loadVodInfo/updateSynopsisPanel debounce (js/data.js,
     * js/browse.js): get_vod_streams never includes plot/genre/cast, so it's
     * fetched per-item, 200ms after the user stops moving (never per card
     * passed through while browsing), and cached.
     */
    private fun fetchVodInfoDebounced(vodId: String) {
        vodInfoJob?.cancel()
        vodInfoJob = viewModelScope.launch {
            delay(200)
            val session = session ?: return@launch
            val repo = authRepository ?: return@launch
            val result = CatalogCache.loadVodInfo(vodId, catalogGeneration) { repo.getVodInfo(session, vodId) }
            val info = result.getOrElse { error ->
                // Kind only: the request URL carries the credentials.
                val kind = if (error is retrofit2.HttpException) "HTTP ${error.code()}" else error.javaClass.simpleName
                android.util.Log.w("BtvVodInfo", "get_vod_info failed: $kind")
                return@launch
            }
            val releaseYear = (info.releaseDate.ifBlank { info.releaseDateAlt }).take(4).takeIf { it.length == 4 }
            val durationText = info.duration.takeIf { it.isNotBlank() }
                ?: info.durationSecs.takeIf { it > 0 }?.let { "${it / 60} min" }

            fun ContentItem.merge(): ContentItem = if (id != vodId) this else copy(
                plot = info.plot.takeIf { it.isNotBlank() } ?: info.description.takeIf { it.isNotBlank() } ?: plot,
                genre = info.genre.takeIf { it.isNotBlank() } ?: genre,
                duration = durationText ?: duration,
                country = info.country.takeIf { it.isNotBlank() } ?: country,
                director = info.director.takeIf { it.isNotBlank() } ?: director,
                cast = info.cast.takeIf { it.isNotBlank() } ?: cast,
                rating = info.rating.takeIf { it.isNotBlank() } ?: rating,
                year = releaseYear ?: year
            )

            currentContentFullList = currentContentFullList.map { it.merge() }
            _uiState.update { state ->
                state.copy(
                    contents = state.contents.map { it.merge() },
                    selectedContent = state.selectedContent?.merge()
                )
            }
            _uiState.value.selectedContent?.takeIf { it.id == vodId }?.let { fetchCastPhotosDebounced(it) }
        }
    }

    /**
     * Cosmetic TMDB cast-photo enrichment (js/data.js fetchTmdbCast/
     * loadSynopsisCastPhotos) - only ever attempted once real cast TEXT is
     * already on screen (from Xtream itself), and only once per item
     * (`castPhotos == null` is "not yet tried", as opposed to `emptyList()`
     * which would mean "tried, no match" - never re-fetched needlessly).
     */
    private fun fetchCastPhotosDebounced(item: ContentItem) {
        if (item.cast.isNullOrBlank() || item.castPhotos != null) return
        val mediaType = if (item.contentKind == ContentKind.SERIES || _uiState.value.contentType == ContentType.SERIES) "tv" else "movie"
        castPhotoJob?.cancel()
        castPhotoJob = viewModelScope.launch {
            delay(200)
            val photos = tmdbRepository.fetchCast(mediaType, item.name, item.year) ?: emptyList()

            fun ContentItem.merge(): ContentItem = if (id != item.id) this else copy(castPhotos = photos)

            currentContentFullList = currentContentFullList.map { it.merge() }
            _uiState.update { state ->
                state.copy(
                    contents = state.contents.map { it.merge() },
                    selectedContent = state.selectedContent?.merge()
                )
            }
        }
    }

    private fun buildStreamUrlFor(streamId: String, type: ContentType, extension: String? = null): String? {
        val s = session ?: return null
        return authRepository?.buildStreamUrl(s, streamId, streamKindFor(type), extension)
    }

    private var liveEpgRequestJob: Job? = null
    private val liveEpgPersistedAt = HashMap<String, Long>()

    init {
        liveEpgDiskCache?.let { cache ->
            viewModelScope.launch {
                try {
                    cache.prune(System.currentTimeMillis())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("BtvEpg", "Disk guide prune failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * "Now playing" for the live rows on screen, whatever list they come
     * from (category, Tout afficher, search, favourites, history). Each entry
     * is one row's channel ids in preference order: siblings of a grouped
     * row are only asked when the preferred one has no current programme.
     *
     * Memory first (instant), then disk (a restart or an eviction doesn't
     * blank the list), then the network - one request at a time, 150ms
     * apart, only for guides older than CatalogCache's five minutes. Called
     * again on scroll and every minute, it also rolls a row over to the next
     * programme.
     */
    fun requestLiveEpg(rows: List<List<String>>) {
        if (rows.isEmpty()) return
        liveEpgRequestJob?.cancel()
        liveEpgRequestJob = viewModelScope.launch {
            val ids = rows.flatten().distinct()
            val known = HashMap<String, List<EpgProgramInfo>>()
            ids.forEach { id -> CatalogCache.peekEpg(id)?.let { known[id] = it } }
            val accountKey = com.btv.data.db.AccountScope.global.key.value
            val disk = liveEpgDiskCache
            if (disk != null && accountKey != null) {
                val missing = ids.filter { it !in known }
                try {
                    known.putAll(disk.read(accountKey, missing, System.currentTimeMillis()))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("BtvEpg", "Disk guide unavailable: ${error.javaClass.simpleName}")
                }
            }
            applyLiveEpg(known)

            for (row in rows) {
                for (id in row) {
                    val now = System.currentTimeMillis()
                    if (!CatalogCache.isEpgFresh(id, now)) {
                        val listings = fetchEpgListings(id).getOrNull()?.value ?: continue
                        applyLiveEpg(mapOf(id to listings))
                        persistLiveEpg(accountKey, id, listings)
                        delay(LIVE_EPG_ENRICH_DELAY_MS)
                    }
                    val listings = CatalogCache.peekEpg(id) ?: known[id]
                    if (listings?.any { it.startTs <= now && it.stopTs > now } == true) break
                }
            }
        }
    }

    private fun persistLiveEpg(accountKey: String?, channelId: String, listings: List<EpgProgramInfo>) {
        val disk = liveEpgDiskCache ?: return
        accountKey ?: return
        val now = System.currentTimeMillis()
        if (now - (liveEpgPersistedAt[channelId] ?: 0L) < LIVE_EPG_PERSIST_INTERVAL_MS) return
        liveEpgPersistedAt[channelId] = now
        viewModelScope.launch {
            try {
                disk.write(accountKey, channelId, listings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("BtvEpg", "Disk guide not saved: ${error.javaClass.simpleName}")
            }
        }
    }

    /** One state update for many channels; rows whose programme ended lose their stale line. */
    private fun applyLiveEpg(listingsByChannel: Map<String, List<EpgProgramInfo>>) {
        if (listingsByChannel.isEmpty()) return
        val now = System.currentTimeMillis()
        val current = listingsByChannel.mapValues { (_, listings) ->
            listings.firstOrNull { it.startTs <= now && it.stopTs > now }
        }
        fun apply(c: ContentItem): ContentItem {
            if (c.id !in current) return c
            val program = current[c.id]
            if (program == null) {
                val ended = c.epgEndTime != null && c.epgEndTime <= now
                return if (ended) c.copy(badge = null, epgProgress = null, epgStartTime = null, epgEndTime = null) else c
            }
            if (c.epgStartTime == program.startTs && c.epgEndTime == program.stopTs && c.badge != null) return c
            val timeRange = formatEpgTimeRange(program.startTs, program.stopTs)
            val progress = if (program.stopTs > program.startTs) {
                ((now - program.startTs).toFloat() / (program.stopTs - program.startTs)).coerceIn(0f, 1f)
            } else null
            return c.copy(
                badge = listOf(timeRange, program.title).filter { it.isNotBlank() }.joinToString("  "),
                epgProgress = progress,
                epgStartTime = program.startTs,
                epgEndTime = program.stopTs
            )
        }
        fun List<ContentItem>.applied(): List<ContentItem> {
            var changed = false
            val result = map { item -> apply(item).also { if (it !== item) changed = true } }
            return if (changed) result else this
        }
        currentContentFullList = currentContentFullList.applied()
        _uiState.update { state ->
            val contents = state.contents.applied()
            val selected = state.selectedContent?.let(::apply)
            if (contents === state.contents && selected === state.selectedContent) state
            else state.copy(contents = contents, selectedContent = selected)
        }
    }

    private val epgTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun formatEpgTimeRange(startTs: Long, stopTs: Long): String {
        if (startTs <= 0 || stopTs <= 0) return ""
        return "${epgTimeFormat.format(startTs)}–${epgTimeFormat.format(stopTs)}"
    }

    /** Cache-first, same as Tizen's `loadLiveEpgListings` (js/data.js). */
    private suspend fun fetchEpgListings(channelId: String, forceRefresh: Boolean = false): Result<EpgCacheSnapshot<List<EpgProgramInfo>>> {
        val session = session ?: return Result.failure(IllegalStateException("Session absente"))
        val repo = authRepository ?: return Result.failure(IllegalStateException("Dépôt absent"))
        return CatalogCache.loadEpg(channelId, catalogGeneration, forceRefresh) {
            repo.getShortEpg(session, channelId).map { rawListings ->
                rawListings.map { raw ->
                    EpgProgramInfo(
                        title = decodeEpgText(raw.title),
                        startTs = (raw.startTimestamp.toLongOrNull() ?: 0L) * 1000L,
                        stopTs = (raw.stopTimestamp.toLongOrNull() ?: 0L) * 1000L
                    )
                }.sortedBy { it.startTs }
            }
        }
    }

    private fun XtreamChannel.toContentItem(): ContentItem {
        val url = session?.let { s -> authRepository?.buildStreamUrl(s, streamId, "live") }
        return ContentItem(
            id = streamId,
            name = name,
            posterUrl = streamIcon,
            backdropUrl = streamIcon,
            streamUrl = url
        )
    }

    private fun XtreamVod.toContentItem(): ContentItem {
        val url = session?.let { s ->
            val ext = resolveExtension(containerExtension, s.userInfo.allowedOutputFormats, "mp4")
            authRepository?.buildStreamUrl(s, streamId, "movie", ext)
        }
        return ContentItem(
            id = streamId,
            name = name,
            posterUrl = streamIcon ?: movieImage,
            backdropUrl = movieImage ?: streamIcon,
            plot = plot,
            cast = cast,
            rating = rating,
            year = year,
            duration = duration,
            streamUrl = url
        )
    }

    private fun XtreamSeries.toContentItem(): ContentItem {
        // A series card has no stream of its own - opening it drills into
        // seasons then episodes (get_series_info), see openSeriesSeasons.
        return ContentItem(
            id = seriesId,
            name = title,
            posterUrl = cover,
            backdropUrl = cover,
            plot = plot,
            cast = cast,
            rating = rating,
            year = year,
            genre = genre,
            streamUrl = null,
            contentKind = ContentKind.SERIES
        )
    }

    // ---------------------------------------------------------------------
    // Series -> seasons -> episodes drill-down (js/browse.js openSeriesSeasons/
    // openSeasonEpisodes/mapSeasonEpisodes) - the flat get_series list only
    // has enough to show the series card; get_series_info is what maps
    // season/episode numbers to real, playable stream ids.
    // ---------------------------------------------------------------------

    private val seriesInfoCache = mutableMapOf<String, com.btv.data.model.XtreamSeriesInfoResponse>()

    private suspend fun fetchSeriesInfo(seriesId: String): com.btv.data.model.XtreamSeriesInfoResponse? {
        seriesInfoCache[seriesId]?.let { return it }
        val repo = authRepository ?: return null
        val sess = session ?: return null
        return repo.getSeriesInfo(sess, seriesId).getOrNull()?.also { seriesInfoCache[seriesId] = it }
    }

    private fun pushDrillFrame() {
        val current = _uiState.value
        _uiState.update {
            it.copy(contentDrillStack = it.contentDrillStack + ContentDrillFrame(current.screenTitle, current.contents))
        }
    }

    /** Back while drilled into seasons/episodes pops one level instead of leaving the category - returns false when there's nothing to pop. */
    fun popContentDrill(): Boolean {
        val frame = _uiState.value.contentDrillStack.lastOrNull() ?: return false
        currentContentFullList = frame.contents
        _uiState.update {
            it.copy(
                contentDrillStack = it.contentDrillStack.dropLast(1),
                contents = frame.contents,
                screenTitle = frame.screenTitle,
                selectedContentId = frame.contents.firstOrNull()?.id,
                selectedContent = frame.contents.firstOrNull()
            )
        }
        return true
    }

    private fun openSeriesSeasons(item: ContentItem) {
        // The user is looking at the series: no need to keep flagging it.
        newEpisodesRepository?.let { repo -> viewModelScope.launch { com.btv.util.guarded("BtvBrowse", "New episodes acknowledge") { repo.acknowledge(item.id) } } }
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val info = fetchSeriesInfo(item.id)
            if (info == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val seasonMetaByNum = info.seasons.associateBy { it.seasonNumber }
            val seasonItems = info.episodes.keys.sortedBy { it.toIntOrNull() ?: 0 }.map { num ->
                val meta = seasonMetaByNum[num]
                val episodeCount = info.episodes[num]?.size ?: 0
                ContentItem(
                    id = "${item.id}_s$num",
                    name = meta?.name?.takeIf { it.isNotBlank() } ?: "Saison $num",
                    posterUrl = meta?.coverBig ?: meta?.cover ?: item.posterUrl,
                    backdropUrl = meta?.coverBig ?: meta?.cover ?: item.posterUrl,
                    plot = meta?.overview,
                    badge = "$episodeCount ép.",
                    contentKind = ContentKind.SEASON,
                    episodeIds = info.episodes[num]?.map { it.id }.orEmpty(),
                    seriesId = item.id,
                    seriesName = item.name,
                    seasonNum = num.toIntOrNull()
                )
            }
            pushDrillFrame()
            currentContentFullList = seasonItems
            _uiState.update {
                it.copy(
                    contents = seasonItems,
                    screenTitle = item.name,
                    selectedContentId = seasonItems.firstOrNull()?.id,
                    selectedContent = seasonItems.firstOrNull(),
                    isLoading = false
                )
            }
        }
    }

    private fun openSeasonEpisodes(season: ContentItem) {
        val seriesId = season.seriesId ?: return
        val seasonNum = season.seasonNum ?: return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val info = fetchSeriesInfo(seriesId)
            if (info == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val episodes = buildEpisodeItems(info, seriesId, seasonNum, season.seriesName)
            pushDrillFrame()
            currentContentFullList = episodes
            _uiState.update {
                it.copy(
                    contents = episodes,
                    screenTitle = season.name,
                    selectedContentId = episodes.firstOrNull()?.id,
                    selectedContent = episodes.firstOrNull(),
                    isLoading = false
                )
            }
        }
    }

    private fun buildEpisodeItems(info: com.btv.data.model.XtreamSeriesInfoResponse, seriesId: String, seasonNum: Int, seriesName: String? = null): List<ContentItem> {
        val sess = session ?: return emptyList()
        val episodesRaw = info.episodes[seasonNum.toString()] ?: emptyList()
        return episodesRaw.map { ep ->
            val ext = resolveExtension(ep.containerExtension, sess.userInfo.allowedOutputFormats, "mp4")
            val url = authRepository?.buildStreamUrl(sess, ep.id, "series", ext)
            ContentItem(
                id = ep.id,
                name = ep.title?.takeIf { it.isNotBlank() } ?: "Épisode ${ep.episodeNum}",
                posterUrl = ep.info?.movieImage,
                backdropUrl = ep.info?.movieImage,
                plot = ep.info?.plot,
                duration = ep.info?.duration,
                badge = "S${seasonNum}E${ep.episodeNum}",
                streamUrl = url,
                contentKind = ContentKind.PLAYABLE,
                seriesId = seriesId,
                seriesName = seriesName,
                seasonNum = seasonNum
            )
        }
    }

    // ---------------------------------------------------------------------
    // Mock data (used when there's no session, and for REPLAY/FAVORITES
    // which don't have a real data source wired up yet)
    // ---------------------------------------------------------------------

    private fun loadMockData() {
        viewModelScope.launch {
            val screenTitle = screenTitleFor(_uiState.value.contentType)

            val quickAccessCategories = listOf(
                BrowseCategory(CATEGORY_CONTINUE_WATCHING, "Continuer à regarder", 4, isQuickAccess = true),
                BrowseCategory(CATEGORY_FAVORITES, "Favoris", 2, isQuickAccess = true),
                BrowseCategory(CATEGORY_SHOW_ALL, "Tout afficher", 0, isQuickAccess = true),
                BrowseCategory("recently_added", "Nouveautés", 0, isQuickAccess = true),
            )

            val regularCategories = listOf(
                "|FR| NOUVEAUTES", "|FR| COMEDIE", "|FR| COMEDIE DRAMATIQUE",
                "|FR| ACTION|AVENTURE", "|FR| CRIME|DRAME|THRILLER", "|FR| DRAME|SOAP|ROMANCE",
                "|FR| SF|FANTASTIQUE", "|FR| HORREUR|MYSTERE", "|FR| ANIMATION|FAMILIAL",
                "|FR| MARVEL|DC COMICS", "|FR| MANGA|ANIME", "|FR| HISTOIRE|BIOGRAPHIE",
                "|FR| DOCUMENTAIRE", "|FR| TELE-REALITE", "|FR| SERIES ANCIENNES",
                "|FR| SERIES 4K UHD", "|FR| SERIES ASIATIQUES"
            ).mapIndexed { index, name ->
                BrowseCategory("cat_$index", name)
            }

            val mockCategories = quickAccessCategories + regularCategories

            _uiState.update { state ->
                state.copy(
                    categories = mockCategories,
                    selectedCategoryId = quickAccessCategories.first().id,
                    screenTitle = screenTitle,
                    isLoading = false,
                    error = null
                )
            }

            loadContentsForCategory(quickAccessCategories.first().id)
        }
    }

    private fun loadContentsForCategory(categoryId: String) {
        viewModelScope.launch {
            val isSeries = _uiState.value.contentType == ContentType.SERIES
            val mockContents = when (categoryId) {
                CATEGORY_CONTINUE_WATCHING -> generateMockContents(categoryId, 5, "À regarder", hasProgress = true, isSeries = isSeries)
                CATEGORY_FAVORITES -> generateMockContents(categoryId, 3, "Favori", isSeries = isSeries)
                CATEGORY_SHOW_ALL -> generateMockContents(categoryId, 20, "Contenu", isSeries = isSeries)
                "recently_added" -> generateMockContents(categoryId, 8, "Nouveauté", isSeries = isSeries)
                else -> generateMockContents(categoryId, (5..15).random(), "Titre", isSeries = isSeries)
            }

            currentContentFullList = mockContents
            _uiState.update { state ->
                state.copy(
                    contents = mockContents,
                    selectedContentId = mockContents.firstOrNull()?.id,
                    selectedContent = mockContents.firstOrNull()
                )
            }
        }
    }

    private fun generateMockContents(
        categoryId: String,
        count: Int,
        prefix: String,
        hasProgress: Boolean = false,
        isSeries: Boolean = false
    ): List<ContentItem> {
        return (1..count).map { index ->
            ContentItem(
                id = "${categoryId}_$index",
                name = "$prefix $index",
                posterUrl = "https://picsum.photos/300/450?random=$index",
                backdropUrl = "https://picsum.photos/1280/720?random=${index + 100}",
                plot = "Voici la description détaillée du contenu $index. C'est une production premium avec une histoire captivante.",
                cast = "Acteur Principal, Actrice 2, Acteur 3",
                rating = (6.5 + (index % 40) * 0.1).toString(),
                year = (2020 + (index % 5)).toString(),
                duration = "${90 + (index % 90)} min",
                genre = "Action, Aventure, Drame",
                country = "France",
                director = "Réalisateur Principal",
                playbackProgress = if (hasProgress) (20 + (index % 80)) / 100f else null,
                isWatched = hasProgress,
                badge = if (isSeries) "S1E$index" else null
            )
        }
    }

    private fun generateMockPrograms(channelId: String): List<EpgProgram> {
        val titles = listOf(
            "Journal du matin", "Magazine info", "Série policière", "Film de l'après-midi",
            "Jeu télévisé", "Journal du soir", "Série phare", "Film de soirée", "Talk-show nocturne"
        )
        val genres = listOf("Info", "Magazine", "Policier", "Film", "Divertissement", "Série", "Talk-show")

        val now = System.currentTimeMillis()
        var cursor = now - 4 * 60 * 60 * 1000L

        return titles.mapIndexed { index, title ->
            val durationMinutes = 45 + (index * 17) % 105
            val start = cursor
            val end = start + durationMinutes * 60 * 1000L
            cursor = end
            EpgProgram(
                id = "${channelId}_prog_$index",
                channelId = channelId,
                title = title,
                description = "Description de \"$title\" sur cette chaîne.",
                startTime = start,
                endTime = end,
                genre = genres[index % genres.size]
            )
        }
    }
}

/** A Rediffusion program id is "<stream id>_<start timestamp>". */
internal fun replayChannelIdOf(programId: String): String = programId.substringBeforeLast('_')

/** "1 h 30", "45 min". */
internal fun formatReplayDuration(durationMs: Long): String {
    val minutes = (durationMs / 60_000L).toInt().coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h ${"%02d".format(rest)}"
    }
}
