package com.btv.ui.browse

import com.btv.data.cache.CatalogCache
import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamChannel
import com.btv.data.repository.AuthRepository
import com.btv.data.store.CatalogSection
import com.btv.data.store.isAdultCategoryName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which live channels belong to an adult category, shared by every Browse
 * screen for the catalog generation (favourites and history launch
 * channels without their category). Some panels refuse to list the adult
 * category itself; the full live list still carries each channel's
 * category_id, so it is the fallback.
 */
internal object AdultChannelIndex {
    /** What a launch check can conclude. */
    sealed interface Verdict {
        data object NotAdult : Verdict
        data object Adult : Verdict
        /** Adult categories exist but membership couldn't be established: treat as adult. */
        data object Unknown : Verdict
    }

    private val mutex = Mutex()
    private var generation = -1L
    private var adultCategoryIds: Set<String>? = null
    private var adultChannelIds: Set<String>? = null

    suspend fun check(
        channelId: String,
        launchedFromCategoryId: String?,
        session: AuthSession,
        repo: AuthRepository,
        loadCategory: suspend (categoryId: String) -> Result<List<XtreamChannel>>
    ): Verdict = mutex.withLock {
        val currentGeneration = CatalogCache.generationToken()
        if (generation != currentGeneration) {
            generation = currentGeneration
            adultCategoryIds = null
            adultChannelIds = null
        }
        val categories = adultCategoryIds ?: CatalogCache.loadCategories(CatalogSection.LIVE, currentGeneration) {
            repo.getLiveCategories(session)
        }.getOrNull()?.filter { isAdultCategoryName(it.categoryName) }?.mapTo(HashSet()) { it.categoryId }
            ?.also { adultCategoryIds = it }
            // Without the category list nothing is known; the panel is
            // unreachable anyway, so the launch would fail on its own.
            ?: return@withLock Verdict.NotAdult
        if (categories.isEmpty()) return@withLock Verdict.NotAdult
        if (launchedFromCategoryId != null) {
            if (launchedFromCategoryId in categories) return@withLock Verdict.Adult
            // Opened from a real, non-adult panel category: nothing to check.
            if (launchedFromCategoryId.all(Char::isDigit)) return@withLock Verdict.NotAdult
        }
        val known = adultChannelIds ?: resolveAdultChannels(categories, session, repo, loadCategory)
            ?.also { adultChannelIds = it }
            ?: return@withLock Verdict.Unknown
        if (channelId in known) Verdict.Adult else Verdict.NotAdult
    }

    /** Already-resolved adult channel ids, for filtering the player's zap list. */
    fun knownAdultChannelIds(): Set<String> =
        if (generation == CatalogCache.generationToken()) adultChannelIds.orEmpty() else emptySet()

    private suspend fun resolveAdultChannels(
        categories: Set<String>,
        session: AuthSession,
        repo: AuthRepository,
        loadCategory: suspend (categoryId: String) -> Result<List<XtreamChannel>>
    ): Set<String>? {
        val perCategory = categories.map { loadCategory(it) }
        if (perCategory.all { it.isSuccess }) {
            return perCategory.flatMapTo(HashSet()) { result -> result.getOrThrow().map { it.streamId } }
        }
        val ids = HashSet<String>()
        return repo.streamCatalog(session, "get_live_streams", XtreamChannel.serializer()) { channel ->
            if (channel.categoryId in categories) ids += channel.streamId
            true
        }.map { ids }.getOrNull()
    }
}
