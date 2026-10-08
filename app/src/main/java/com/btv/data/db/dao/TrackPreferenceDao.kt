package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.btv.data.db.entities.TrackPreferenceEntity

@Dao
interface TrackPreferenceDao {

    /** Every row of the account, for the sync between devices. */
    @Query("SELECT * FROM track_preference WHERE accountKey = :accountKey")
    suspend fun getAllForSync(accountKey: String): List<TrackPreferenceEntity>

    @Query("DELETE FROM track_preference WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId")
    suspend fun delete(accountKey: String, type: String, streamId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: TrackPreferenceEntity)

    @Query("SELECT * FROM track_preference WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId LIMIT 1")
    suspend fun get(accountKey: String, type: String, streamId: String): TrackPreferenceEntity?

    @Query("DELETE FROM track_preference WHERE accountKey = :accountKey")
    suspend fun deleteAll(accountKey: String)
}
