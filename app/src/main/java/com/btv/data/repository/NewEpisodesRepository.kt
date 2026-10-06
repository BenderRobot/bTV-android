package com.btv.data.repository

import com.btv.data.db.AccountScope
import com.btv.data.db.dao.FavoritesDao
import com.btv.data.db.dao.SeriesEpisodeSnapshotDao
import com.btv.data.db.entities.SeriesEpisodeSnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * New episodes of favorite series (Tizen checkFavoriteSeriesForNewEpisodes,
 * js/data.js). Each check compares against the snapshot taken by the
 * PREVIOUS check, never against the moment the series was favorited, so a
 * series checked for the first time is never reported.
 */
class NewEpisodesRepository(
    private val snapshotDao: SeriesEpisodeSnapshotDao,
    private val favoritesDao: FavoritesDao,
    private val accountScope: AccountScope = AccountScope.global
) {
    data class NewEpisodes(val seriesId: String, val seriesName: String, val count: Int)

    /** Unacknowledged new-episode count per series id. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun newEpisodeCounts(): Flow<Map<String, Int>> =
        accountScope.key.flatMapLatest { key ->
            if (key == null) flowOf(emptyMap())
            else snapshotDao.observePending(key).map { rows -> rows.associate { it.seriesId to decode(it.newEpisodeIds).size } }
        }

    /** The series has been opened: stop flagging it (Tizen acknowledgeNewEpisodes). */
    suspend fun acknowledge(seriesId: String) {
        val key = accountScope.key.value ?: return
        snapshotDao.acknowledge(key, seriesId)
    }

    /**
     * Checks favorite series one at a time (cheap IPTV panels answer bursts
     * with HTTP 429). [fetchEpisodeIds] returns null when the series could
     * not be loaded; its snapshot is then left untouched.
     */
    suspend fun checkFavoriteSeries(fetchEpisodeIds: suspend (seriesId: String) -> List<String>?): List<NewEpisodes> {
        val key = accountScope.key.value ?: return emptyList()
        val favorites = favoritesDao.getFavoritesByType(key, SERIES_TYPE).first()
        val found = ArrayList<NewEpisodes>()
        for (favorite in favorites) {
            val episodeIds = fetchEpisodeIds(favorite.streamId)?.filter { it.isNotEmpty() }?.distinct() ?: continue
            if (episodeIds.isEmpty()) continue
            // Account switched while the network call was in flight.
            if (accountScope.key.value != key) return found
            val current = episodeIds.toHashSet()
            val previous = snapshotDao.get(key, favorite.streamId)
            var pending = previous?.let { decode(it.newEpisodeIds) }.orEmpty()
            if (previous != null) {
                val known = decode(previous.knownEpisodeIds).toHashSet()
                val added = episodeIds.filter { it !in known }
                if (added.isNotEmpty()) found += NewEpisodes(favorite.streamId, favorite.name, added.size)
                // Keep still-unacknowledged ids from earlier checks, unless the
                // panel has since removed them.
                pending = (pending + added).filter { it in current }.distinct()
            }
            snapshotDao.upsert(
                SeriesEpisodeSnapshotEntity(
                    accountKey = key,
                    seriesId = favorite.streamId,
                    knownEpisodeIds = encode(episodeIds),
                    newEpisodeIds = encode(pending),
                    checkedAt = System.currentTimeMillis()
                )
            )
        }
        return found
    }

    private fun encode(ids: List<String>): String = ids.joinToString(",")

    private fun decode(value: String): List<String> = value.split(',').filter { it.isNotEmpty() }

    companion object {
        // Favorites are written with ContentType.name (BrowseViewModel.toggleFavorite).
        private const val SERIES_TYPE = "SERIES"
    }
}
