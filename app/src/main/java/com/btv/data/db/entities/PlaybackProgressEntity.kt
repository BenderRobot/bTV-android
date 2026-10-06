package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_progress",
    primaryKeys = ["accountKey", "type", "streamId"],
    indices = [
        Index(value = ["accountKey", "type"]),
        Index("lastProgressedAt")
    ]
)
data class PlaybackProgressEntity(
    val accountKey: String,
    val streamId: String,
    val type: String, // "live", "movie", "series"
    val progressMs: Long, // Position en millisecondes
    val durationMs: Long, // Durée totale en millisecondes
    val progressPercent: Float = if (durationMs > 0) (progressMs * 100f / durationMs) else 0f,
    val isCompleted: Boolean = false,
    val lastProgressedAt: Long = System.currentTimeMillis(),
    val seriesId: String? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    // Same fix as HistoryEntity.containerExtension - resuming from "Continuer
    // à regarder" rebuilds the stream URL from just the streamId, and needs
    // the real container to avoid UnrecognizedInputFormatException.
    val containerExtension: String? = null
)
