package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.btv.data.db.entities.TrackPreferenceEntity

@Dao
interface TrackPreferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: TrackPreferenceEntity)

    @Query("SELECT * FROM track_preference WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId LIMIT 1")
    suspend fun get(accountKey: String, type: String, streamId: String): TrackPreferenceEntity?

    @Query("DELETE FROM track_preference WHERE accountKey = :accountKey")
    suspend fun deleteAll(accountKey: String)
}
