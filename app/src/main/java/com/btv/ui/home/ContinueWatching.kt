package com.btv.ui.home

import com.btv.data.cache.CatalogCache
import com.btv.data.db.entities.HistoryEntity
import com.btv.data.db.entities.PlaybackProgressEntity
import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository
import com.btv.data.store.PreferencesStore
import com.btv.data.store.isAdultCategoryName
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase
import com.btv.ui.browse.AdultChannelIndex
import com.btv.ui.browse.panelStartOf
import com.btv.ui.player.PlayerLaunchRequest
import com.btv.ui.player.ZapItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class ContinueKind { MOVIE, EPISODE, REPLAY, LIVE }

/** One card of Home's "Continuer à regarder": what was started, ready to play again. */
data class ContinueItem(
    val key: String,
    val kind: ContinueKind,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    /** 0..1, null for live channels. */
    val progress: Float?,
    val lastActivityAt: Long,
    val request: PlayerLaunchRequest,
    /** Live / replay: the channel the parental check is made on, and its category. */
    val channelId: String? = null,
    val channelCategoryId: String? = null
)

/** Most recent first, at most this many cards. */
internal const val CONTINUE_MAX_ITEMS = 20

/** Browse's own lists: never a series name, even when recorded as the category. */
internal val LIST_NAMES = setOf(
    "Continuer à regarder", "Favoris", "Récemment consultés", "Consulté récemment", "Tout afficher", "Nouveautés"
)

/** Live has no progress: only the last few channels watched. */
internal const val CONTINUE_MAX_LIVE = 4

/** Saved progress and history, per ContentType name ("VOD", "SERIES", "LIVE", "REPLAY"). */
internal data class ContinueSources(
    val progress: Map<String, List<PlaybackProgressEntity>>,
    val history: Map<String, List<HistoryEntity>>
)

/**
 * Builds the row from what the player saved - never invented data:
 * films and episodes in progress (one card per series, its latest episode),
 * started replays, and the last live channels. Adult-looking channels are
 * left out: Home must never become a way around the parental PIN.
 *
 * [streamUrl] builds a VOD/series/live address; [replayUrl] a timeshift one
 * from the replay id ("channelId_startSeconds") and its duration.
 */
internal fun buildContinueItems(
    sources: ContinueSources,
    streamUrl: (type: String, streamId: String, extension: String?) -> String?,
    replayUrl: (channelId: String, startMs: Long, durationMs: Long) -> String?
): List<ContinueItem> {
    fun historyOf(type: String) = sources.history[type].orEmpty().associateBy { it.streamId }
    fun HistoryEntity.looksAdult() = isAdultCategoryName(categoryName) || isAdultCategoryName(name)
    fun PlaybackProgressEntity.fraction() = (progressPercent / 100f).coerceIn(0f, 1f)
    // Last time it was opened or played, whichever is later: the row follows what was watched last.
    fun recency(progress: PlaybackProgressEntity, history: HistoryEntity) = maxOf(progress.lastProgressedAt, history.lastViewedAt)

    val items = ArrayList<ContinueItem>()

    val movieHistory = historyOf("VOD")
    sources.progress["VOD"].orEmpty().forEach { progress ->
        val history = movieHistory[progress.streamId] ?: return@forEach
        val url = streamUrl("VOD", progress.streamId, progress.containerExtension ?: history.containerExtension) ?: return@forEach
        items += ContinueItem(
            key = "VOD:${progress.streamId}",
            kind = ContinueKind.MOVIE,
            title = history.name,
            subtitle = "Film",
            posterUrl = history.posterUrl,
            progress = progress.fraction(),
            lastActivityAt = recency(progress, history),
            request = PlayerLaunchRequest(
                streamUrl = url,
                contentId = progress.streamId,
                progressType = "VOD",
                contentName = history.name,
                zapList = listOf(ZapItem(progress.streamId, history.name, history.posterUrl, url)),
                posterUrl = history.posterUrl,
                categoryId = history.categoryId,
                categoryName = history.categoryName
            )
        )
    }

    // Series: the latest episode of each series only.
    val seriesHistory = sources.history["SERIES"].orEmpty()
    val episodeHistory = seriesHistory.associateBy { it.streamId }
    // Every episode opened counts, finished ones too: watching an episode to
    // the end still makes its series the last thing watched.
    val seriesLastViewed = seriesHistory
        .groupBy { it.seriesId ?: it.streamId }
        .mapValues { (_, episodes) -> episodes.maxOf { it.lastViewedAt } }
    // Launched from a series, the history's category is the series name - but
    // an episode started from a list ("Continuer à regarder", "Favoris"...)
    // recorded that list's name instead; another episode may still have it.
    val seriesNames = seriesHistory
        .filter { it.seriesId != null && it.categoryName.isNotBlank() && it.categoryName !in LIST_NAMES }
        .sortedByDescending { it.lastViewedAt }
        .associate { it.seriesId to it.categoryName }
    sources.progress["SERIES"].orEmpty()
        .mapNotNull { progress -> episodeHistory[progress.streamId]?.let { progress to it } }
        .groupBy { (_, history) -> history.seriesId ?: history.streamId }
        .values
        .mapNotNull { episodes -> episodes.maxByOrNull { (progress, history) -> recency(progress, history) } }
        .forEach { (progress, history) ->
            val url = streamUrl("SERIES", progress.streamId, progress.containerExtension ?: history.containerExtension) ?: return@forEach
            val seriesName = history.seriesId?.let { seriesNames[it] }
            items += ContinueItem(
                key = "SERIES:${progress.streamId}",
                kind = ContinueKind.EPISODE,
                title = seriesName ?: history.name,
                subtitle = if (seriesName != null) history.name else "Série",
                posterUrl = history.posterUrl,
                progress = progress.fraction(),
                lastActivityAt = maxOf(recency(progress, history), seriesLastViewed[history.seriesId ?: history.streamId] ?: 0L),
                request = PlayerLaunchRequest(
                    streamUrl = url,
                    contentId = progress.streamId,
                    progressType = "SERIES",
                    contentName = history.name,
                    zapList = listOf(ZapItem(progress.streamId, history.name, history.posterUrl, url)),
                    posterUrl = history.posterUrl,
                    categoryId = history.seriesId ?: history.categoryId,
                    categoryName = history.categoryName,
                    seriesId = history.seriesId,
                    seriesName = seriesName,
                    seasonNum = history.seasonNumber
                )
            )
        }

    val replayHistory = historyOf("REPLAY")
    sources.progress["REPLAY"].orEmpty().forEach { progress ->
        val history = replayHistory[progress.streamId] ?: return@forEach
        if (history.looksAdult()) return@forEach
        val channelId = progress.streamId.substringBeforeLast('_')
        val startMs = (progress.streamId.substringAfterLast('_').toLongOrNull() ?: return@forEach) * 1000L
        val url = replayUrl(channelId, startMs, progress.durationMs) ?: return@forEach
        items += ContinueItem(
            key = "REPLAY:${progress.streamId}",
            kind = ContinueKind.REPLAY,
            title = history.name,
            subtitle = history.categoryName.ifBlank { "Rediffusion" },
            posterUrl = history.posterUrl,
            progress = progress.fraction(),
            lastActivityAt = recency(progress, history),
            request = PlayerLaunchRequest(
                streamUrl = url,
                contentId = progress.streamId,
                progressType = "REPLAY",
                contentName = history.name,
                zapList = listOf(ZapItem(progress.streamId, history.name, history.posterUrl, url)),
                posterUrl = history.posterUrl,
                categoryId = history.categoryId,
                categoryName = history.categoryName
            ),
            channelId = channelId
        )
    }

    // Live: the last channels watched; zapping from one walks the others.
    val live = sources.history["LIVE"].orEmpty()
        .sortedByDescending { it.lastViewedAt }
        .filterNot { it.looksAdult() }
        .mapNotNull { history ->
            streamUrl("LIVE", history.streamId, history.containerExtension)?.let { history to it }
        }
        .take(CONTINUE_MAX_LIVE)
    val liveZap = live.map { (history, url) -> ZapItem(history.streamId, history.name, history.posterUrl, url) }
    live.forEach { (history, url) ->
        items += ContinueItem(
            key = "LIVE:${history.streamId}",
            kind = ContinueKind.LIVE,
            title = history.name,
            subtitle = "En direct",
            posterUrl = history.posterUrl,
            progress = null,
            lastActivityAt = history.lastViewedAt,
            request = PlayerLaunchRequest(
                streamUrl = url,
                contentId = history.streamId,
                progressType = "LIVE",
                contentName = history.name,
                zapList = liveZap,
                posterUrl = history.posterUrl,
                // The player finds the channel's other qualities from its category.
                categoryId = history.categoryId,
                categoryName = history.categoryName
            ),
            channelId = history.streamId,
            channelCategoryId = history.categoryId
        )
    }

    return items.sortedByDescending { it.lastActivityAt }.take(CONTINUE_MAX_ITEMS)
}

/** Live wiring of [buildContinueItems]: re-emits whenever progress or history changes. */
fun continueWatchingFlow(
    session: AuthSession,
    authRepository: AuthRepository,
    preferencesStore: PreferencesStore,
    progressUseCase: GetPlaybackProgressUseCase,
    historyUseCase: GetRecentlyWatchedUseCase
): Flow<List<ContinueItem>> {
    val progressFlow = combine(
        progressUseCase.getInProgress("VOD"),
        progressUseCase.getInProgress("SERIES"),
        progressUseCase.getInProgress("REPLAY")
    ) { vod, series, replay -> mapOf("VOD" to vod, "SERIES" to series, "REPLAY" to replay) }
    val historyFlow = combine(
        historyUseCase.getRecentlyWatched(100),
        historyUseCase.getRecentlyWatchedSeries(100),
        historyUseCase.getRecentlyWatchedLive(20),
        historyUseCase.getRecentlyWatchedReplay(100)
    ) { vod, series, live, replay -> mapOf("VOD" to vod, "SERIES" to series, "LIVE" to live, "REPLAY" to replay) }
    return combine(progressFlow, historyFlow, preferencesStore.replayPanelOffsetMs) { progress, history, panelOffset ->
        buildContinueItems(
            ContinueSources(progress, history),
            streamUrl = { type, id, extension ->
                val kind = when (type) { "VOD" -> "movie"; "LIVE" -> "live"; else -> "series" }
                authRepository.buildStreamUrl(session, id, kind, extension)
            },
            replayUrl = { channelId, startMs, durationMs ->
                // Same fallback as Rediffusion when no guide has taught the offset yet.
                val offset = panelOffset ?: java.util.TimeZone.getDefault().getOffset(startMs).toLong()
                val minutes = ((durationMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                authRepository.buildTimeshiftUrl(session, channelId, panelStartOf(startMs, offset), minutes)
            }
        )
    }
}

/**
 * Last line of the parental control for live and replay cards: the same
 * check Browse makes before launching a channel from favourites/history.
 */
suspend fun isAdultChannel(item: ContinueItem, session: AuthSession, authRepository: AuthRepository): Boolean {
    val channelId = item.channelId ?: return false
    val verdict = AdultChannelIndex.check(channelId, item.channelCategoryId, session, authRepository) { categoryId ->
        CatalogCache.loadLiveStreams(categoryId, CatalogCache.generationToken()) {
            authRepository.getLiveStreams(session, categoryId)
        }
    }
    return verdict != AdultChannelIndex.Verdict.NotAdult
}
