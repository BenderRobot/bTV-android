package com.btv.data.repository

import com.btv.data.db.dao.HistoryDao
import com.btv.data.db.AccountScope
import com.btv.data.db.entities.HistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

class HistoryRepository(private val historyDao: HistoryDao, private val accountScope: AccountScope = AccountScope.global) {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getAllHistory(): Flow<List<HistoryEntity>> = accountScope.key.flatMapLatest { key ->
        if (key == null) flowOf(emptyList()) else historyDao.getAllHistory(key)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getHistoryByType(type: String, limit: Int = 50): Flow<List<HistoryEntity>> =
        accountScope.key.flatMapLatest { key ->
            if (key == null) flowOf(emptyList()) else historyDao.getHistoryByType(key, type, limit)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getGroupedHistory(): Flow<List<HistoryEntity>> = accountScope.key.flatMapLatest { key ->
        if (key == null) flowOf(emptyList()) else historyDao.getGroupedHistory(key)
    }

    suspend fun addToHistory(
        streamId: String,
        type: String,
        name: String,
        categoryId: String,
        categoryName: String,
        posterUrl: String? = null,
        seriesId: String? = null,
        episodeNumber: Int? = null,
        seasonNumber: Int? = null,
        containerExtension: String? = null
    ) {
        val accountKey = accountScope.requireKey()
        val existing = historyDao.getByStreamId(accountKey, type, streamId)
        if (existing != null) {
            historyDao.update(
                existing.copy(
                    type = type,
                    name = name,
                    categoryId = categoryId.ifEmpty { existing.categoryId },
                    categoryName = categoryName.ifEmpty { existing.categoryName },
                    posterUrl = posterUrl ?: existing.posterUrl,
                    seriesId = seriesId ?: existing.seriesId,
                    episodeNumber = episodeNumber ?: existing.episodeNumber,
                    seasonNumber = seasonNumber ?: existing.seasonNumber,
                    viewCount = existing.viewCount + 1,
                    lastViewedAt = System.currentTimeMillis(),
                    // Backfills entries saved before this field existed.
                    containerExtension = containerExtension ?: existing.containerExtension
                )
            )
        } else {
            val history = HistoryEntity(
                accountKey = accountKey,
                streamId = streamId,
                type = type,
                name = name,
                categoryId = categoryId,
                categoryName = categoryName,
                posterUrl = posterUrl,
                seriesId = seriesId,
                episodeNumber = episodeNumber,
                seasonNumber = seasonNumber,
                containerExtension = containerExtension
            )
            historyDao.insert(history)
        }
    }

    suspend fun removeFromHistory(streamId: String, type: String) {
        historyDao.deleteByStreamId(accountScope.requireKey(), type, streamId)
    }

    /** Tizen removeFromRecent on a grouped series card: every episode of it. */
    suspend fun removeSeriesFromHistory(seriesId: String, type: String) {
        historyDao.deleteBySeriesId(accountScope.requireKey(), type, seriesId)
    }

    suspend fun getHistoryCount(): Int {
        return historyDao.getHistoryCount(accountScope.requireKey())
    }

    suspend fun clearOlderThan(daysBefore: Int = 30) {
        val cutoffTime = System.currentTimeMillis() - (daysBefore * 24 * 60 * 60 * 1000L)
        historyDao.deleteOlderThan(accountScope.requireKey(), cutoffTime)
    }

    suspend fun clearAll() {
        historyDao.deleteAll(accountScope.requireKey())
    }
}
