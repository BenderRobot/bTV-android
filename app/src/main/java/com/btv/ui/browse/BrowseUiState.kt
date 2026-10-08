package com.btv.ui.browse

import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamVod
import com.btv.data.model.XtreamSeries

data class BrowseUiState(
    val categories: List<BrowseCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val selectedContentId: String? = null,
    val categorySearch: String = "",
    val contentSearch: String = "",
    val isSidebarVisible: Boolean = true,
    val contents: List<ContentItem> = emptyList(),
    val selectedContent: ContentItem? = null,
    val isLoading: Boolean = false,
    val loadingProgress: String? = null,
    val error: String? = null,
    val retryTarget: BrowseRetryTarget? = null,
    val contentType: ContentType = ContentType.VOD,
    val screenTitle: String = "",
    val epgChannelId: String? = null,
    val epgPrograms: List<EpgProgram> = emptyList(),
    val epgError: String? = null,
    val isEpgLoading: Boolean = false,
    val liveEpgChannelId: String? = null,
    val liveEpgPrograms: List<EpgProgram> = emptyList(),
    val isLiveEpgLoading: Boolean = false,
    val liveEpgError: String? = null,
    /** Series -> seasons -> episodes drill-down (js/browse.js railStack): snapshot to restore on Back. */
    val contentDrillStack: List<ContentDrillFrame> = emptyList(),
    /** Rediffusion: what the selected channel is airing now (restartable from its beginning). */
    val replayOnAir: ContentItem? = null,
    /** Rediffusion: false when the channel has no guide and its archive is offered hour by hour. */
    val replayHasGuide: Boolean = true,
    val replayArchiveDays: Int? = null,
    /** Rediffusion: the "Continuer" list (programs from several channels, no day tabs). */
    val replayIsContinue: Boolean = false
) {
    /** Tizen "remove" sub-focus: only offered inside the history category. */
    val canRemoveFromHistory: Boolean
        get() = selectedCategoryId == CATEGORY_RECENTLY_VIEWED_ID && selectedContent != null

    /** Favorites is a navigation section, never the type written into playback/history. */
    val mediaType: ContentType
        get() = if (contentType == ContentType.FAVORITES) {
            categories.firstOrNull { it.id == selectedCategoryId }?.type ?: ContentType.VOD
        } else contentType
}

internal const val CATEGORY_RECENTLY_VIEWED_ID = "recently_viewed"

fun ContentItem.canFavorite(type: ContentType): Boolean = when (type) {
    ContentType.SERIES -> contentKind == ContentKind.SERIES
    ContentType.VOD, ContentType.LIVE -> contentKind == ContentKind.PLAYABLE && seriesId == null
    else -> false
}

fun ContentItem.canMarkWatched(type: ContentType): Boolean = when (contentKind) {
    ContentKind.PLAYABLE -> streamUrl != null &&
        (type == ContentType.VOD || (type == ContentType.SERIES && seriesId != null))
    // Marking a season marks every one of its episodes.
    ContentKind.SEASON -> type == ContentType.SERIES && episodeIds.isNotEmpty()
    ContentKind.SERIES -> false
}

/** Tizen favorites rail badge for a series with unacknowledged new episodes. */
fun ContentItem.withNewEpisodesBadge(newEpisodeCounts: Map<String, Int>): ContentItem {
    if (contentKind != ContentKind.SERIES) return this
    val count = newEpisodeCounts[id]?.takeIf { it > 0 } ?: return this
    return copy(badge = if (count == 1) "NOUVEAU" else "+$count NOUVEAUX")
}

/** Tizen isItemWatched: a season is watched once all its episodes are (js/browse.js openSeriesSeasons). */
fun ContentItem.withWatchedState(type: ContentType, watchedIds: Set<String>): ContentItem {
    val watched = when {
        contentKind == ContentKind.SEASON -> episodeIds.isNotEmpty() && watchedIds.containsAll(episodeIds)
        else -> canMarkWatched(type) && id in watchedIds
    }
    return if (watched == isWatched) this else copy(isWatched = watched)
}

data class ContentDrillFrame(
    val screenTitle: String,
    val contents: List<ContentItem>
)

data class BrowseCategory(
    val id: String,
    val name: String,
    val itemCount: Int = 0,
    val type: ContentType = ContentType.VOD,
    val isQuickAccess: Boolean = false,
    /** What the sidebar search matches; Rediffusion leaves out the |XX| and (..) tags. */
    val searchName: String = name,
    /** Small second line (Rediffusion: archive length and category). */
    val subtitle: String? = null,
    /** Pinned by the user to the top of the sidebar (☰ / long OK). */
    val isPinned: Boolean = false
)

/**
 * Sidebar order: quick-access entries, then pinned categories in pin order,
 * then everything else in its usual order. Pins of categories absent from
 * this list (hidden, language-filtered) are simply not shown.
 */
fun arrangeCategories(
    quickAccess: List<BrowseCategory>,
    real: List<BrowseCategory>,
    pinnedIds: List<String>
): List<BrowseCategory> {
    val byId = real.associateBy { it.id }
    val pinned = pinnedIds.distinct().mapNotNull { byId[it]?.copy(isPinned = true) }
    val pinnedSet = pinned.mapTo(HashSet()) { it.id }
    return quickAccess + pinned + real.filter { it.id !in pinnedSet }.map { it.copy(isPinned = false) }
}

data class ContentItem(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    /** TMDB-enriched cast photos, if a match was found (see BrowseViewModel.fetchCastPhotosDebounced) - null until resolved, empty/absent forever falls back to the plain [cast] text. */
    val castPhotos: List<com.btv.data.model.TmdbCastPerson>? = null,
    val rating: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val genre: String? = null,
    val country: String? = null,
    val director: String? = null,
    val playbackProgress: Float? = null,
    val isWatched: Boolean = false,
    val badge: String? = null,
    val streamUrl: String? = null,
    /** 0f-1f progress of the current EPG program (live channels only), set by the background enrichment loop. */
    val epgProgress: Float? = null,
    val epgStartTime: Long? = null,
    val epgEndTime: Long? = null,
    val contentKind: ContentKind = ContentKind.PLAYABLE,
    /** Set on SEASON cards and on episode (PLAYABLE) items so the player's zap list can be rebuilt for "next episode" (js/browse.js buildSeasonZapList). */
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonNum: Int? = null,
    /** SEASON cards only: episode ids, to show the season as watched once all of them are. */
    val episodeIds: List<String> = emptyList()
)

/** PLAYABLE = movie/episode/channel (has or resolves to a streamUrl); SERIES = drill into seasons; SEASON = drill into episodes. */
enum class ContentKind {
    PLAYABLE, SERIES, SEASON
}

enum class ContentType {
    VOD, SERIES, LIVE, FAVORITES, REPLAY
}

enum class BrowseRetryTarget { CATALOG, CONTENT }

data class EpgProgram(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long,
    val genre: String = ""
) {
    fun isCurrentlyAiring(now: Long = System.currentTimeMillis()): Boolean =
        now >= startTime && now < endTime

    fun progressFraction(now: Long = System.currentTimeMillis()): Float {
        if (!isCurrentlyAiring(now)) return 0f
        val total = (endTime - startTime).toFloat()
        if (total <= 0f) return 0f
        return ((now - startTime).toFloat() / total).coerceIn(0f, 1f)
    }
}
