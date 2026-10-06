package com.btv.domain.usecase

import com.btv.data.db.entities.EpgProgramEntity
import com.btv.data.repository.EpgRepository
import kotlinx.coroutines.flow.Flow

class GetEpgUseCase(private val epgRepository: EpgRepository) {

    fun getProgramsByChannel(channelId: String): Flow<List<EpgProgramEntity>> {
        return epgRepository.getProgramsByChannel(channelId)
    }

    fun getProgramsByTime(startTime: Long, endTime: Long): Flow<List<EpgProgramEntity>> {
        return epgRepository.getProgramsByTime(startTime, endTime)
    }

    suspend fun getCurrentProgram(channelId: String): EpgProgramEntity? {
        return epgRepository.getCurrentProgram(channelId)
    }
}
