package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.btv.data.db.entities.ReplayChannelEntity
import com.btv.data.db.entities.ReplayProgramEntity

@Dao
interface ReplayDao {

    @Query("SELECT * FROM replay_channels WHERE accountKey = :accountKey")
    suspend fun getChannels(accountKey: String): List<ReplayChannelEntity>

    @Query("DELETE FROM replay_channels WHERE accountKey = :accountKey")
    suspend fun deleteChannels(accountKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ReplayChannelEntity>)

    @Query("SELECT * FROM replay_programs WHERE accountKey = :accountKey AND streamId = :streamId ORDER BY startTs ASC")
    suspend fun getPrograms(accountKey: String, streamId: String): List<ReplayProgramEntity>

    @Query("DELETE FROM replay_programs WHERE accountKey = :accountKey AND streamId = :streamId")
    suspend fun deletePrograms(accountKey: String, streamId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<ReplayProgramEntity>)

    /** Programs past any archive window: [cutoffSeconds] is a panel timestamp. */
    @Query("DELETE FROM replay_programs WHERE stopTs < :cutoffSeconds")
    suspend fun deleteProgramsEndedBefore(cutoffSeconds: Long)
}
