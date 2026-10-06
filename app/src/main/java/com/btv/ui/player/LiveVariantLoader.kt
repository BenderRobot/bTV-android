package com.btv.ui.player

import com.btv.data.cache.CatalogCache
import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamChannel
import com.btv.data.repository.AuthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Every channel that could stand in for [selected]: its category (through
 * the session cache Browse already filled), plus the panel-wide sibling
 * index - favourites, history and search have no panel category, and a UHD
 * version often sits in a separate "4K" category anyway.
 */
suspend fun loadLiveVariantCandidates(
    authRepository: AuthRepository,
    session: AuthSession,
    selected: ZapItem,
    categoryId: String
): List<ZapItem> {
    val fromCategory = if (categoryId.isNotEmpty() && categoryId.all(Char::isDigit)) {
        CatalogCache.loadLiveStreams(categoryId, CatalogCache.generationToken()) {
            authRepository.getLiveStreams(session, categoryId)
        }.getOrNull().orEmpty().map { it.toZapItem(authRepository, session) }
    } else emptyList()
    val fromIndex = LiveSiblingIndex.siblingsOf(liveChannelKey(selected.name), authRepository, session)
    return fromCategory + fromIndex
}

private fun XtreamChannel.toZapItem(authRepository: AuthRepository, session: AuthSession) =
    ZapItem(streamId, name, streamIcon, authRepository.buildStreamUrl(session, streamId, "live"))

/**
 * Same-name channel groups across the whole live catalog, built once per
 * catalog generation by streaming get_live_streams. Only groups of two or
 * more are kept, so the index stays a small fraction of the catalog.
 */
private object LiveSiblingIndex {
    private val mutex = Mutex()
    private var generation = -1L
    private var groups: Map<String, List<Pair<String, String>>>? = null

    suspend fun siblingsOf(key: String, authRepository: AuthRepository, session: AuthSession): List<ZapItem> {
        if (key.isEmpty()) return emptyList()
        val members = mutex.withLock {
            val currentGeneration = CatalogCache.generationToken()
            if (generation != currentGeneration) {
                groups = null
                generation = currentGeneration
            }
            (groups ?: build(authRepository, session)?.also { groups = it })?.get(key)
        } ?: return emptyList()
        return members.map { (id, name) -> ZapItem(id, name, null, authRepository.buildStreamUrl(session, id, "live")) }
    }

    private suspend fun build(authRepository: AuthRepository, session: AuthSession): Map<String, List<Pair<String, String>>>? {
        val all = HashMap<String, MutableList<Pair<String, String>>>()
        authRepository.streamCatalog(session, "get_live_streams", XtreamChannel.serializer()) { channel ->
            val key = liveChannelKey(channel.name)
            if (key.isNotEmpty()) all.getOrPut(key) { ArrayList(1) } += channel.streamId to channel.name
            true
        }.getOrNull() ?: return null
        return all.filterValues { it.size > 1 }
    }
}
