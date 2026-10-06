package com.btv.data.repository

import com.btv.data.db.dao.EpgDao
import com.btv.data.db.entities.EpgProgramEntity
import kotlinx.coroutines.flow.Flow

class EpgRepository(private val epgDao: EpgDao) {

    fun getProgramsByChannel(channelId: String): Flow<List<EpgProgramEntity>> {
        return epgDao.getProgramsByChannel(channelId)
    }

    fun getProgramsByTime(startTime: Long, endTime: Long): Flow<List<EpgProgramEntity>> {
        return epgDao.getProgramsByTime(startTime, endTime)
    }

    suspend fun getCurrentProgram(channelId: String, now: Long = System.currentTimeMillis()): EpgProgramEntity? {
        return epgDao.getCurrentProgram(channelId, now)
    }

    suspend fun insertPrograms(programs: List<EpgProgramEntity>) {
        epgDao.insertPrograms(programs)
    }

    suspend fun clearOlderThan(cutoffTime: Long) {
        epgDao.deleteOlderThan(cutoffTime)
    }
}
