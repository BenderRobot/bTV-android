package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.btv.data.db.entities.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: HistoryEntity)

    @Update
    suspend fun update(history: HistoryEntity)

    @Delete
    suspend fun delete(history: HistoryEntity)

    @Query("SELECT * FROM history WHERE accountKey = :accountKey ORDER BY lastViewedAt DESC")
    fun getAllHistory(accountKey: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE accountKey = :accountKey AND type = :type ORDER BY lastViewedAt DESC LIMIT :limit")
    fun getHistoryByType(accountKey: String, type: String, limit: Int = 50): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId LIMIT 1")
    suspend fun getByStreamId(accountKey: String, type: String, streamId: String): HistoryEntity?

    @Query("DELETE FROM history WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId")
    suspend fun deleteByStreamId(accountKey: String, type: String, streamId: String)

    @Query("DELETE FROM history WHERE accountKey = :accountKey AND type = :type AND seriesId = :seriesId")
    suspend fun deleteBySeriesId(accountKey: String, type: String, seriesId: String)

    @Query("DELETE FROM history WHERE accountKey = :accountKey AND lastViewedAt < :cutoffTime")
    suspend fun deleteOlderThan(accountKey: String, cutoffTime: Long)

    @Query("SELECT COUNT(*) FROM history WHERE accountKey = :accountKey")
    suspend fun getHistoryCount(accountKey: String): Int

    @Query("DELETE FROM history WHERE accountKey = :accountKey")
    suspend fun deleteAll(accountKey: String)

    @Query("""
        SELECT *
        FROM history
        WHERE accountKey = :accountKey AND type IN ('SERIES', 'VOD')
        ORDER BY lastViewedAt DESC
    """)
    fun getGroupedHistory(accountKey: String): Flow<List<HistoryEntity>>
}
