package com.btv.data.repository

import com.btv.data.db.dao.PlaybackProgressDao
import com.btv.data.db.AccountScope
import com.btv.data.db.entities.PlaybackProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class PlaybackProgressRepository(private val playbackProgressDao: PlaybackProgressDao, private val accountScope: AccountScope = AccountScope.global) {

    companion object {
        // Exact port of Tizen's PROGRESS_DONE_RATIO/PROGRESS_MIN_SECONDS (js/data.js):
        // beyond this ratio, treat as finished (no resume offered, next time starts
        // from 0); below this many seconds in, not worth remembering at all.
        private const val PROGRESS_DONE_RATIO = 0.92f
        private const val PROGRESS_MIN_SECONDS = 15
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getProgress(streamId: String, type: String): Flow<PlaybackProgressEntity?> =
        accountScope.key.flatMapLatest { key ->
            if (key == null) flowOf(null) else playbackProgressDao.getByStreamIdFlow(key, type, streamId)
        }

    suspend fun getProgressSync(streamId: String, type: String): PlaybackProgressEntity? =
        playbackProgressDao.getByStreamId(accountScope.requireKey(), type, streamId)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getInProgressByType(type: String): Flow<List<PlaybackProgressEntity>> =
        accountScope.key.flatMapLatest { key ->
            if (key == null) flowOf(emptyList()) else playbackProgressDao.getInProgressByType(key, type)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getCompletedIds(type: String): Flow<Set<String>> =
        accountScope.key.flatMapLatest { key ->
            if (key == null) flowOf(emptySet()) else playbackProgressDao.getCompletedIds(key, type).map { it.toSet() }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getCompletedItems(): Flow<List<PlaybackProgressEntity>> =
        accountScope.key.flatMapLatest { key ->
            if (key == null) flowOf(emptyList()) else playbackProgressDao.getCompletedItems(key)
        }

    /**
     * Port of Tizen's saveProgress (js/data.js): >=92% watched is treated as
     * finished (position reset to 0 - no partial resume once done, matching
     * Tizen exactly), under 15s in is too early to be worth remembering at
     * all (entry removed rather than kept at ~0), otherwise saved as-is.
     */
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
        val accountKey = accountScope.requireKey()
        if (durationMs <= 0) return
        val ratio = progressMs.toFloat() / durationMs.toFloat()
        val positionSeconds = progressMs / 1000

        if (positionSeconds < PROGRESS_MIN_SECONDS && ratio < PROGRESS_DONE_RATIO) {
            playbackProgressDao.deleteByStreamId(accountKey, type, streamId)
            return
        }

        val isDone = ratio >= PROGRESS_DONE_RATIO
        val effectivePositionMs = if (isDone) 0L else progressMs
        val progressPercent = if (isDone) 100f else (progressMs * 100f / durationMs)
        val existing = playbackProgressDao.getByStreamId(accountKey, type, streamId)

        if (existing != null) {
            playbackProgressDao.update(
                existing.copy(
                    progressMs = effectivePositionMs,
                    durationMs = durationMs,
                    progressPercent = progressPercent,
                    isCompleted = isDone,
                    lastProgressedAt = System.currentTimeMillis(),
                    containerExtension = containerExtension ?: existing.containerExtension
                )
            )
        } else {
            val progress = PlaybackProgressEntity(
                accountKey = accountKey,
                streamId = streamId,
                type = type,
                progressMs = effectivePositionMs,
                durationMs = durationMs,
                progressPercent = progressPercent,
                isCompleted = isDone,
                seriesId = seriesId,
                episodeNumber = episodeNumber,
                seasonNumber = seasonNumber,
                containerExtension = containerExtension
            )
            playbackProgressDao.insert(progress)
        }
    }

    suspend fun updateProgress(streamId: String, type: String, progressMs: Long) {
        val accountKey = accountScope.requireKey()
        val existing = playbackProgressDao.getByStreamId(accountKey, type, streamId)
        if (existing != null) {
            val progressPercent = if (existing.durationMs > 0) {
                (progressMs * 100f / existing.durationMs)
            } else 0f

            playbackProgressDao.updateProgress(
                accountKey = accountKey,
                type = type,
                streamId = streamId,
                progressMs = progressMs,
                progressPercent = progressPercent
            )
        }
    }

    suspend fun markAsCompleted(streamId: String, type: String) {
        playbackProgressDao.markAsCompleted(accountScope.requireKey(), type, streamId)
    }

    /** Manual Tizen-style Vu/Non vu: a new item may have no progress row yet. */
    suspend fun toggleWatched(streamId: String, type: String, containerExtension: String? = null): Boolean {
        val accountKey = accountScope.requireKey()
        val existing = playbackProgressDao.getByStreamId(accountKey, type, streamId)
        if (existing?.isCompleted == true) {
            playbackProgressDao.deleteByStreamId(accountKey, type, streamId)
            return false
        }
        playbackProgressDao.insert(
            (existing ?: PlaybackProgressEntity(
                accountKey = accountKey, streamId = streamId, type = type,
                progressMs = 0L, durationMs = 0L
            )).copy(
                progressMs = 0L,
                progressPercent = 100f,
                isCompleted = true,
                lastProgressedAt = System.currentTimeMillis(),
                containerExtension = containerExtension ?: existing?.containerExtension
            )
        )
        return true
    }

    /** Whole-season Vu/Non vu: every episode gets the same state. */
    suspend fun setWatched(streamIds: List<String>, type: String, watched: Boolean) {
        val accountKey = accountScope.requireKey()
        for (streamId in streamIds) {
            val existing = playbackProgressDao.getByStreamId(accountKey, type, streamId)
            if (!watched) {
                if (existing != null) playbackProgressDao.deleteByStreamId(accountKey, type, streamId)
            } else if (existing?.isCompleted != true) {
                playbackProgressDao.insert(
                    (existing ?: PlaybackProgressEntity(
                        accountKey = accountKey, streamId = streamId, type = type,
                        progressMs = 0L, durationMs = 0L
                    )).copy(
                        progressMs = 0L,
                        progressPercent = 100f,
                        isCompleted = true,
                        lastProgressedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    suspend fun deleteProgress(streamId: String, type: String) {
        playbackProgressDao.deleteByStreamId(accountScope.requireKey(), type, streamId)
    }

    suspend fun getInProgressCount(): Int {
        return playbackProgressDao.getInProgressCount(accountScope.requireKey())
    }

    suspend fun clearOlderThan(daysBefore: Int = 90) {
        val cutoffTime = System.currentTimeMillis() - (daysBefore * 24 * 60 * 60 * 1000L)
        playbackProgressDao.deleteOlderThan(accountScope.requireKey(), cutoffTime)
    }

    suspend fun clearAll() {
        playbackProgressDao.deleteAll(accountScope.requireKey())
    }
}
