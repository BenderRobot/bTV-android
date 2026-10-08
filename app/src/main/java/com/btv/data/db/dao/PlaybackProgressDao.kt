package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.btv.data.db.entities.PlaybackProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {

    /** Every row of the account, for the sync between devices. */
    @Query("SELECT * FROM playback_progress WHERE accountKey = :accountKey")
    suspend fun getAllForSync(accountKey: String): List<PlaybackProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: PlaybackProgressEntity)

    @Update
    suspend fun update(progress: PlaybackProgressEntity)

    @Delete
    suspend fun delete(progress: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId LIMIT 1")
    suspend fun getByStreamId(accountKey: String, type: String, streamId: String): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId")
    fun getByStreamIdFlow(accountKey: String, type: String, streamId: String): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE accountKey = :accountKey AND type = :type AND isCompleted = 0 ORDER BY lastProgressedAt DESC")
    fun getInProgressByType(accountKey: String, type: String): Flow<List<PlaybackProgressEntity>>

    @Query("SELECT * FROM playback_progress WHERE accountKey = :accountKey AND isCompleted = 1 ORDER BY lastProgressedAt DESC")
    fun getCompletedItems(accountKey: String): Flow<List<PlaybackProgressEntity>>

    /** Ids only: Browse marks watched posters without loading a row per card. */
    @Query("SELECT streamId FROM playback_progress WHERE accountKey = :accountKey AND type = :type AND isCompleted = 1")
    fun getCompletedIds(accountKey: String, type: String): Flow<List<String>>

    @Query("DELETE FROM playback_progress WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId")
    suspend fun deleteByStreamId(accountKey: String, type: String, streamId: String)

    @Query("DELETE FROM playback_progress WHERE accountKey = :accountKey AND lastProgressedAt < :cutoffTime")
    suspend fun deleteOlderThan(accountKey: String, cutoffTime: Long)

    @Query("SELECT COUNT(*) FROM playback_progress WHERE accountKey = :accountKey AND isCompleted = 0")
    suspend fun getInProgressCount(accountKey: String): Int

    @Query("DELETE FROM playback_progress WHERE accountKey = :accountKey")
    suspend fun deleteAll(accountKey: String)

    @Query("UPDATE playback_progress SET progressMs = :progressMs, progressPercent = :progressPercent, lastProgressedAt = :timestamp WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId")
    suspend fun updateProgress(
        accountKey: String,
        type: String,
        streamId: String,
        progressMs: Long,
        progressPercent: Float,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE playback_progress SET isCompleted = 1 WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId")
    suspend fun markAsCompleted(accountKey: String, type: String, streamId: String)
}
