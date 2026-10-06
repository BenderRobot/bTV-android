package com.btv.domain.usecase

import com.btv.data.db.entities.PlaybackProgressEntity
import com.btv.data.repository.PlaybackProgressRepository
import kotlinx.coroutines.flow.Flow

class GetPlaybackProgressUseCase(private val playbackProgressRepository: PlaybackProgressRepository) {

    fun getProgress(streamId: String, type: String): Flow<PlaybackProgressEntity?> {
        return playbackProgressRepository.getProgress(streamId, type)
    }

    suspend fun getProgressSync(streamId: String, type: String): PlaybackProgressEntity? {
        return playbackProgressRepository.getProgressSync(streamId, type)
    }

    fun getInProgress(type: String): Flow<List<PlaybackProgressEntity>> {
        return playbackProgressRepository.getInProgressByType(type)
    }

    suspend fun saveProgress(
        streamId: String,
        type: String,
        progressMs: Long,
        durationMs: Long,
        seriesId: String? = null,
        episodeNumber: Int? = null,
        seasonNumber: Int? = null,
        containerExtension: String? = null
    ) {
        playbackProgressRepository.saveProgress(
            streamId = streamId,
            type = type,
            progressMs = progressMs,
            durationMs = durationMs,
            seriesId = seriesId,
            episodeNumber = episodeNumber,
            seasonNumber = seasonNumber,
            containerExtension = containerExtension
        )
    }

    suspend fun updateProgress(streamId: String, type: String, progressMs: Long) {
        playbackProgressRepository.updateProgress(streamId, type, progressMs)
    }

    suspend fun markAsCompleted(streamId: String, type: String) {
        playbackProgressRepository.markAsCompleted(streamId, type)
    }

    fun getCompletedIds(type: String): Flow<Set<String>> = playbackProgressRepository.getCompletedIds(type)

    suspend fun setWatched(streamIds: List<String>, type: String, watched: Boolean) =
        playbackProgressRepository.setWatched(streamIds, type, watched)

    suspend fun toggleWatched(streamId: String, type: String, containerExtension: String? = null): Boolean =
        playbackProgressRepository.toggleWatched(streamId, type, containerExtension)

    // Matches Tizen's PROGRESS_MIN_SECONDS (js/data.js) - saveProgress()
    // already deletes anything below this, so this is really just a null
    // check, but keeping the explicit threshold documents the invariant.
    suspend fun shouldResumePlayback(streamId: String, type: String): Boolean {
        val progress = playbackProgressRepository.getProgressSync(streamId, type)
        return progress != null && !progress.isCompleted && progress.progressMs >= 15_000L
    }

    suspend fun getResumePosition(streamId: String, type: String): Long {
        val progress = playbackProgressRepository.getProgressSync(streamId, type)
        return progress?.progressMs ?: 0L
    }
}
