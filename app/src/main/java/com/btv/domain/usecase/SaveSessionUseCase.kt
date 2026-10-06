package com.btv.domain.usecase

import com.btv.data.db.entities.UserSessionEntity
import com.btv.data.repository.SessionRepository
import kotlinx.coroutines.flow.Flow

class SaveSessionUseCase(private val sessionRepository: SessionRepository) {

    suspend fun saveSession(
        username: String,
        serverUrl: String,
        userId: String,
        expirationDate: Long,
        createdAt: String,
        userEmail: String? = null,
        userStatus: String = "active",
        activeConnections: Int = 1,
        maxConnections: Int? = null,
        isTrial: Int = 0,
        allowedOutputFormats: String? = null
    ) {
        sessionRepository.saveOrUpdateSession(
            username = username,
            serverUrl = serverUrl,
            userId = userId,
            expirationDate = expirationDate,
            createdAt = createdAt,
            userEmail = userEmail,
            userStatus = userStatus,
            activeConnections = activeConnections,
            maxConnections = maxConnections,
            isTrial = isTrial,
            allowedOutputFormats = allowedOutputFormats
        )
    }

    fun getCurrentSession(): Flow<UserSessionEntity?> {
        return sessionRepository.getCurrentSession()
    }

    suspend fun getCurrentSessionSync(): UserSessionEntity? {
        return sessionRepository.getCurrentSessionSync()
    }

    suspend fun isSessionValid(): Boolean {
        return sessionRepository.isSessionValid()
    }

    suspend fun updateLastLoginTime(username: String) {
        sessionRepository.updateLastLoginTime(username)
    }

    suspend fun deleteSession() {
        sessionRepository.deleteSession()
    }

    suspend fun deleteExpiredSessions() {
        sessionRepository.deleteExpiredSessions()
    }
}
