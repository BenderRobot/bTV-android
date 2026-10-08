package com.btv.domain.usecase

import com.btv.data.db.entities.HistoryEntity
import com.btv.data.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow

class GetRecentlyWatchedUseCase(private val historyRepository: HistoryRepository) {

    // "VOD"/"SERIES"/"LIVE" - matches ContentType.name, which is what every
    // writer (BrowseViewModel.trackRecentlyWatched/toggleFavorite) actually
    // stores. These previously hardcoded "movie"/"series"/"live", which
    // never matched (case- and, for movies, word-different), so these
    // methods always returned empty - a latent bug, since nothing called
    // them until now.
    fun getRecentlyWatched(limit: Int = 50): Flow<List<HistoryEntity>> {
        return historyRepository.getHistoryByType("VOD", limit)
    }

    fun getRecentlyWatchedSeries(limit: Int = 50): Flow<List<HistoryEntity>> {
        return historyRepository.getHistoryByType("SERIES", limit)
    }

    fun getRecentlyWatchedLive(limit: Int = 50): Flow<List<HistoryEntity>> {
        return historyRepository.getHistoryByType("LIVE", limit)
    }

    fun getRecentlyWatchedReplay(limit: Int = 50): Flow<List<HistoryEntity>> {
        return historyRepository.getHistoryByType("REPLAY", limit)
    }

    suspend fun removeFromHistory(streamId: String, type: String) =
        historyRepository.removeFromHistory(streamId, type)

    suspend fun removeSeriesFromHistory(seriesId: String, type: String) =
        historyRepository.removeSeriesFromHistory(seriesId, type)

    fun getGroupedHistory(): Flow<List<HistoryEntity>> {
        return historyRepository.getGroupedHistory()
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
        historyRepository.addToHistory(
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
    }
}
