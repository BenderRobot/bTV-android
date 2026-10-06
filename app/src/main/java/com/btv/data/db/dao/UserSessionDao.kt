package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.btv.data.db.entities.UserSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: UserSessionEntity)

    @Update
    suspend fun update(session: UserSessionEntity)

    @Delete
    suspend fun delete(session: UserSessionEntity)

    @Query("SELECT * FROM user_session WHERE username = :username LIMIT 1")
    suspend fun getByUsername(username: String): UserSessionEntity?

    @Query("SELECT * FROM user_session WHERE username = :username")
    fun getByUsernameFlow(username: String): Flow<UserSessionEntity?>

    @Query("SELECT * FROM user_session LIMIT 1")
    suspend fun getCurrentSession(): UserSessionEntity?

    @Query("SELECT * FROM user_session LIMIT 1")
    fun getCurrentSessionFlow(): Flow<UserSessionEntity?>

    @Query("DELETE FROM user_session")
    suspend fun deleteAll()

    @Query("DELETE FROM user_session WHERE expirationDate < :currentTime")
    suspend fun deleteExpiredSessions(currentTime: Long = System.currentTimeMillis())

    @Query("UPDATE user_session SET lastLoginAt = :timestamp WHERE username = :username")
    suspend fun updateLastLoginTime(username: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM user_session")
    suspend fun getSessionCount(): Int
}
