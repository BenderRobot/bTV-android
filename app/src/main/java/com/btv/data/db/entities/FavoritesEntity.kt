package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["accountKey", "type", "streamId"], unique = true),
        Index(value = ["accountKey", "type"]),
        Index("addedAt")
    ]
)
data class FavoritesEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountKey: String,
    val streamId: String,
    val type: String, // "live", "movie", "series"
    val name: String,
    val categoryId: String,
    val categoryName: String,
    val posterUrl: String? = null,
    // Same fix as HistoryEntity.containerExtension - only meaningful for VOD
    // (a favorited series is its own card with no stream of its own; opening
    // it drills into seasons/episodes instead of rebuilding a URL here).
    val containerExtension: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)
