package com.btv.data.db.entities

import androidx.room.Entity

/**
 * Port of Tizen's iptv_trackpref_* (js/data.js saveTrackPref): the audio and
 * subtitle labels last picked by hand for one content, kept across restarts
 * and independent of playback progress (a short viewing still keeps it).
 * A null label means "no choice saved" for that track type.
 */
@Entity(tableName = "track_preference", primaryKeys = ["accountKey", "type", "streamId"])
data class TrackPreferenceEntity(
    val accountKey: String,
    val type: String,
    val streamId: String,
    val audioLabel: String?,
    val subtitleLabel: String?,
    val updatedAt: Long
)
