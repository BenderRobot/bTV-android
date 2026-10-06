package com.btv.data.repository

import com.btv.data.db.dao.UserSessionDao
import com.btv.data.db.entities.UserSessionEntity
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val userSessionDao: UserSessionDao) {

    fun getCurrentSession(): Flow<UserSessionEntity?> = userSessionDao.getCurrentSessionFlow()

    suspend fun getCurrentSessionSync(): UserSessionEntity? = userSessionDao.getCurrentSession()

    suspend fun saveSession(session: UserSessionEntity) {
        userSessionDao.insert(session.copy(password = null))
    }

    suspend fun updateSession(session: UserSessionEntity) {
        userSessionDao.update(session.copy(password = null))
    }

    suspend fun saveOrUpdateSession(
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
        val existing = userSessionDao.getByUsername(username)

        if (existing != null) {
            userSessionDao.update(
                existing.copy(
                    serverUrl = serverUrl,
                    userId = userId,
                    expirationDate = expirationDate,
                    userEmail = userEmail,
                    userStatus = userStatus,
                    activeConnections = activeConnections,
                    maxConnections = maxConnections,
                    isTrial = isTrial,
                    allowedOutputFormats = allowedOutputFormats
                )
            )
        } else {
            val session = UserSessionEntity(
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
            userSessionDao.insert(session)
        }
    }

    suspend fun updateLastLoginTime(username: String) {
        userSessionDao.updateLastLoginTime(username)
    }

    suspend fun deleteSession() {
        userSessionDao.deleteAll()
    }

    suspend fun deleteExpiredSessions() {
        userSessionDao.deleteExpiredSessions()
    }

    suspend fun getSessionCount(): Int {
        return userSessionDao.getSessionCount()
    }

    suspend fun isSessionValid(): Boolean {
        val session = userSessionDao.getCurrentSession() ?: return false
        return session.expirationDate > System.currentTimeMillis()
    }
}
