package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_session")
data class UserSessionEntity(
    @PrimaryKey
    val username: String,
    val password: String? = null,
    val serverUrl: String,
    val userId: String,
    val userEmail: String? = null,
    val userStatus: String = "active",
    val expirationDate: Long,
    val activeConnections: Int = 1,
    val createdAt: String,
    val maxConnections: Int? = null,
    val isTrial: Int = 0,
    val allowedOutputFormats: String? = null,
    val lastLoginAt: Long = System.currentTimeMillis()
)
