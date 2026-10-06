package com.btv.data.db.entities

import androidx.room.Entity

/**
 * Port of Tizen's iptv_known_episodes / iptv_new_episodes (js/data.js):
 * episode ids of a favorite series as of the previous check, and the ids
 * that appeared since and were not acknowledged yet. Ids are comma-joined.
 */
@Entity(tableName = "series_episode_snapshot", primaryKeys = ["accountKey", "seriesId"])
data class SeriesEpisodeSnapshotEntity(
    val accountKey: String,
    val seriesId: String,
    val knownEpisodeIds: String,
    val newEpisodeIds: String,
    val checkedAt: Long
)
