package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.btv.data.db.entities.EpgProgramEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)

    @Query("SELECT * FROM epg_programs WHERE channelId = :channelId ORDER BY startTime ASC")
    fun getProgramsByChannel(channelId: String): Flow<List<EpgProgramEntity>>

    @Query("SELECT * FROM epg_programs WHERE startTime < :endTime AND endTime > :startTime ORDER BY startTime ASC")
    fun getProgramsByTime(startTime: Long, endTime: Long): Flow<List<EpgProgramEntity>>

    @Query("""
        SELECT * FROM epg_programs
        WHERE channelId = :channelId AND startTime <= :now AND endTime > :now
        LIMIT 1
    """)
    suspend fun getCurrentProgram(channelId: String, now: Long): EpgProgramEntity?

    @Query("DELETE FROM epg_programs WHERE endTime < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    @Query("SELECT * FROM epg_programs WHERE channelId IN (:channelIds) AND endTime > :now ORDER BY startTime ASC")
    suspend fun getUpcomingForChannels(channelIds: List<String>, now: Long): List<EpgProgramEntity>

    @Query("DELETE FROM epg_programs WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String)
}
