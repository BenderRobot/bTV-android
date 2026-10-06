package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history",
    indices = [
        Index(value = ["accountKey", "type", "streamId"], unique = true),
        Index(value = ["accountKey", "type"]),
        Index("lastViewedAt")
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountKey: String,
    val streamId: String,
    val type: String, // "live", "movie", "series"
    val name: String,
    val categoryId: String,
    val categoryName: String,
    val posterUrl: String? = null,
    val seriesId: String? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    // Real container ("mp4"/"mkv"/...) resolved once at play time - without
    // this, rebuilding the stream URL from history (Continuer à regarder)
    // always guessed "mp4"/"ts" and broke UnrecognizedInputFormatException
    // for anything else, even though the exact same content played fine
    // when opened fresh from the category grid (which resolves it properly).
    val containerExtension: String? = null,
    val viewCount: Int = 1,
    val lastViewedAt: Long = System.currentTimeMillis()
)
