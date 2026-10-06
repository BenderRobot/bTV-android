package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.btv.data.db.entities.SeriesEpisodeSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesEpisodeSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: SeriesEpisodeSnapshotEntity)

    @Query("SELECT * FROM series_episode_snapshot WHERE accountKey = :accountKey AND seriesId = :seriesId LIMIT 1")
    suspend fun get(accountKey: String, seriesId: String): SeriesEpisodeSnapshotEntity?

    @Query("SELECT * FROM series_episode_snapshot WHERE accountKey = :accountKey AND newEpisodeIds != ''")
    fun observePending(accountKey: String): Flow<List<SeriesEpisodeSnapshotEntity>>

    @Query("UPDATE series_episode_snapshot SET newEpisodeIds = '' WHERE accountKey = :accountKey AND seriesId = :seriesId")
    suspend fun acknowledge(accountKey: String, seriesId: String)

    @Query("DELETE FROM series_episode_snapshot WHERE accountKey = :accountKey")
    suspend fun deleteAll(accountKey: String)
}
