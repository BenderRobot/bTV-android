package com.btv.data.cache

import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamChannel
import com.btv.data.model.XtreamEpgListing
import com.btv.data.model.XtreamSeries
import com.btv.data.model.XtreamVod
import com.btv.data.model.XtreamVodInfo
import com.btv.data.repository.AuthRepository
import com.btv.data.store.CatalogSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EpgProgramInfo(
    val title: String,
    val startTs: Long,
    val stopTs: Long
)

data class EpgCacheSnapshot<T>(val value: T, val isStale: Boolean = false)

private data class TimedEpg<T>(val value: T, val fetchedAtMs: Long)

/** Bounds both the number of category keys and the lists retained in RAM. */
private class BoundedCategoryCache<T>(
    private val maxEntries: Int = 32,
    private val maxItems: Int = 4_000,
    private val maxItemsPerEntry: Int = 2_000
) {
    private val entries = LinkedHashMap<String, List<T>>(16, 0.75f, true)
    private var itemCount = 0

    fun get(categoryId: String): List<T>? = entries[categoryId]

    fun put(categoryId: String, items: List<T>) {
        entries.remove(categoryId)?.let { itemCount -= it.size }
        // A very large category may still be displayed by Browse, but it
        // must not pin that list in the session-wide cache as well.
        if (items.size > maxItemsPerEntry) return
        while (entries.isNotEmpty() && (entries.size >= maxEntries || itemCount + items.size > maxItems)) {
            val oldest = entries.entries.iterator().next()
            itemCount -= oldest.value.size
            entries.remove(oldest.key)
        }
        entries[categoryId] = items
        itemCount += items.size
    }

    fun clear() {
        entries.clear()
        itemCount = 0
    }
}

/**
 * Session-scoped in-memory cache mirroring the Tizen reference app's
 * `categoriesCache`/`epgCache` (js/data.js): category lists are cheap and
 * rarely change, so every screen (Browse, Settings) reads from here first
 * instead of re-hitting the panel. Cleared on logout or manual refresh -
 * same lifetime as Tizen's in-memory layer (no 24h persistent disk cache
 * ported for now, that's a separate enhancement).
 */
object CatalogCache {
    private val _categories = MutableStateFlow<Map<CatalogSection, List<XtreamCategory>>>(emptyMap())
    val categories: StateFlow<Map<CatalogSection, List<XtreamCategory>>> = _categories
    private var generation = 0L

    @Synchronized
    fun generationToken(): Long = generation

    fun categoriesFor(section: CatalogSection): List<XtreamCategory>? = _categories.value[section]

    /** All cache reads and writes share the same session generation and only successful fetches are stored. */
    private suspend fun <T : Any> loadCached(
        expectedGeneration: Long,
        read: () -> T?,
        write: (T) -> Unit,
        fetch: suspend () -> Result<T>
    ): Result<T> {
        synchronized(this) {
            if (generation != expectedGeneration) {
                return Result.failure(IllegalStateException("Catalogue invalidé avant le chargement"))
            }
            read()?.let { return Result.success(it) }
        }
        val result = fetch()
        return synchronized(this) {
            if (generation != expectedGeneration) {
                Result.failure(IllegalStateException("Catalogue invalidé pendant le chargement"))
            } else {
                read()?.let { Result.success(it) } ?: result.also { fetched ->
                    fetched.getOrNull()?.let(write)
                }
            }
        }
    }

    /** A failed fetch stays retryable; a response started before clear() cannot repopulate a new session. */
    suspend fun loadCategories(
        section: CatalogSection,
        expectedGeneration: Long = generationToken(),
        fetch: suspend () -> Result<List<XtreamCategory>>
    ): Result<List<XtreamCategory>> = loadCached(
        expectedGeneration,
        read = { _categories.value[section] },
        write = { _categories.value = _categories.value + (section to it) },
        fetch = fetch
    )

    // A short guide changes while the app is open. Refresh it after five minutes;
    // if that refresh fails, show the old guide explicitly marked as stale.
    private const val SHORT_EPG_TTL_MS = 5 * 60 * 1000L
    // Insertion-order eviction once full, same as Tizen's cacheSet(epgCache, ..., 400).
    private const val EPG_CACHE_MAX = 400
    private val epgByChannel = LinkedHashMap<String, TimedEpg<List<EpgProgramInfo>>>()

    suspend fun loadEpg(
        channelId: String,
        expectedGeneration: Long,
        forceRefresh: Boolean = false,
        nowMs: () -> Long = System::currentTimeMillis,
        fetch: suspend () -> Result<List<EpgProgramInfo>>
    ): Result<EpgCacheSnapshot<List<EpgProgramInfo>>> {
        val previous = synchronized(this) {
            if (generation != expectedGeneration) {
                return Result.failure(IllegalStateException("Catalogue invalidé avant le chargement"))
            }
            epgByChannel[channelId]
        }
        if (!forceRefresh && previous != null && nowMs() - previous.fetchedAtMs < SHORT_EPG_TTL_MS) {
            return Result.success(EpgCacheSnapshot(previous.value))
        }
        val fetched = fetch()
        return synchronized(this) {
            if (generation != expectedGeneration) {
                Result.failure(IllegalStateException("Catalogue invalidé pendant le chargement"))
            } else {
                fetched.fold(
                    onSuccess = { listings ->
                        if (channelId !in epgByChannel && epgByChannel.size >= EPG_CACHE_MAX) {
                            epgByChannel.keys.firstOrNull()?.let(epgByChannel::remove)
                        }
                        epgByChannel[channelId] = TimedEpg(listings, nowMs())
                        Result.success(EpgCacheSnapshot(listings))
                    },
                    onFailure = { failure ->
                        val fallback = epgByChannel[channelId]
                        if (fallback != null) Result.success(EpgCacheSnapshot(fallback.value, isStale = true))
                        else Result.failure(failure)
                    }
                )
            }
        }
    }

    /** Whatever guide is in memory for [channelId], however old - for instant display only. */
    @Synchronized
    fun peekEpg(channelId: String): List<EpgProgramInfo>? = epgByChannel[channelId]?.value

    /** True when [loadEpg] would answer from memory without a request. */
    @Synchronized
    fun isEpgFresh(channelId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        epgByChannel[channelId]?.let { nowMs - it.fetchedAtMs < SHORT_EPG_TTL_MS } == true

    // get_vod_info per movie, cap 300 - matches Tizen's vodInfoCache (js/data.js).
    private const val VOD_INFO_CACHE_MAX = 300
    private val vodInfoByStreamId = LinkedHashMap<String, XtreamVodInfo>()

    suspend fun loadVodInfo(
        vodId: String,
        expectedGeneration: Long,
        fetch: suspend () -> Result<XtreamVodInfo>
    ): Result<XtreamVodInfo> = loadCached(expectedGeneration, { vodInfoByStreamId[vodId] }, { info ->
        if (vodId !in vodInfoByStreamId && vodInfoByStreamId.size >= VOD_INFO_CACHE_MAX) {
            vodInfoByStreamId.keys.firstOrNull()?.let(vodInfoByStreamId::remove)
        }
        vodInfoByStreamId[vodId] = info
    }, fetch)

    // Per-category stream/VOD/series lists are retained only while their
    // section stays within 32 categories and 4,000 items. "Tout afficher"
    // can still scan every category; evicted categories are fetched again on
    // a later visit instead of accumulating indefinitely on a small TV.
    private val vodStreamsByCategory = BoundedCategoryCache<XtreamVod>()
    private val seriesByCategory = BoundedCategoryCache<XtreamSeries>()
    private val liveStreamsByCategory = BoundedCategoryCache<XtreamChannel>()

    suspend fun loadVodStreams(categoryId: String, expectedGeneration: Long, fetch: suspend () -> Result<List<XtreamVod>>): Result<List<XtreamVod>> =
        loadCached(expectedGeneration, { vodStreamsByCategory.get(categoryId) }, { vodStreamsByCategory.put(categoryId, it) }, fetch)

    suspend fun loadSeries(categoryId: String, expectedGeneration: Long, fetch: suspend () -> Result<List<XtreamSeries>>): Result<List<XtreamSeries>> =
        loadCached(expectedGeneration, { seriesByCategory.get(categoryId) }, { seriesByCategory.put(categoryId, it) }, fetch)

    suspend fun loadLiveStreams(categoryId: String, expectedGeneration: Long, fetch: suspend () -> Result<List<XtreamChannel>>): Result<List<XtreamChannel>> =
        loadCached(expectedGeneration, { liveStreamsByCategory.get(categoryId) }, { liveStreamsByCategory.put(categoryId, it) }, fetch)

    // Rediffusion fetches the unfiltered channel list once but retains only
    // archivable channels. A full
    // EPG changes as programs finish and enter the archive, so its cache has
    // a shorter lifetime than the session and a bounded number of channels.
    private var archiveChannelsCache: List<XtreamChannel>? = null
    private const val FULL_EPG_TTL_MS = 15 * 60 * 1000L
    private const val FULL_EPG_CACHE_MAX = 40
    private val fullEpgByChannel = LinkedHashMap<String, TimedEpg<List<XtreamEpgListing>>>()

    suspend fun loadArchiveChannels(expectedGeneration: Long, fetch: suspend () -> Result<List<XtreamChannel>>): Result<List<XtreamChannel>> =
        loadCached(expectedGeneration, { archiveChannelsCache }, { archiveChannelsCache = it }, fetch)

    suspend fun loadFullEpg(
        channelId: String,
        expectedGeneration: Long,
        nowMs: () -> Long = System::currentTimeMillis,
        fetch: suspend () -> Result<List<XtreamEpgListing>>
    ): Result<EpgCacheSnapshot<List<XtreamEpgListing>>> {
        val previous = synchronized(this) {
            if (generation != expectedGeneration) {
                return Result.failure(IllegalStateException("Catalogue invalidé avant le chargement"))
            }
            fullEpgByChannel[channelId]
        }
        if (previous != null && nowMs() - previous.fetchedAtMs < FULL_EPG_TTL_MS) {
            return Result.success(EpgCacheSnapshot(previous.value))
        }
        val fetched = fetch()
        return synchronized(this) {
            if (generation != expectedGeneration) {
                Result.failure(IllegalStateException("Catalogue invalidé pendant le chargement"))
            } else {
                fetched.fold(
                    onSuccess = { listings ->
                        if (channelId !in fullEpgByChannel && fullEpgByChannel.size >= FULL_EPG_CACHE_MAX) {
                            fullEpgByChannel.keys.firstOrNull()?.let(fullEpgByChannel::remove)
                        }
                        fullEpgByChannel[channelId] = TimedEpg(listings, nowMs())
                        Result.success(EpgCacheSnapshot(listings))
                    },
                    onFailure = { failure ->
                        val fallback = fullEpgByChannel[channelId]
                        if (fallback != null) Result.success(EpgCacheSnapshot(fallback.value, isStale = true))
                        else Result.failure(failure)
                    }
                )
            }
        }
    }

    @Synchronized
    fun clear() {
        generation++
        _categories.value = emptyMap()
        epgByChannel.clear()
        vodInfoByStreamId.clear()
        vodStreamsByCategory.clear()
        seriesByCategory.clear()
        liveStreamsByCategory.clear()
        archiveChannelsCache = null
        fullEpgByChannel.clear()
    }
}

/**
 * Mirrors Tizen's `showSplashAndPreload` (js/player.js): sequential
 * live -> movies -> series category preload, one attempt each, skipping
 * any section already cached - so a repeat call after a partial preload
 * only fills in what's missing, and a slow/failed section never blocks
 * the others.
 */
suspend fun preloadCatalogCategories(repository: AuthRepository, session: AuthSession) {
    val sections = listOf(CatalogSection.LIVE, CatalogSection.MOVIES, CatalogSection.SERIES)
    val generation = CatalogCache.generationToken()
    for (section in sections) {
        val result = CatalogCache.loadCategories(section, generation) {
            when (section) {
                CatalogSection.LIVE -> repository.getLiveCategories(session)
                CatalogSection.MOVIES -> repository.getVodCategories(session)
                CatalogSection.SERIES -> repository.getSeriesCategories(session)
            }
        }
        if (result.isFailure && CatalogCache.generationToken() != generation) return
    }
}
