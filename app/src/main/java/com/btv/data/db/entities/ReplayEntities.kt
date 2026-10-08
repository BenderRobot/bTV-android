package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Rediffusion's channel list (live channels with an archive), per account,
 * so the sidebar shows at once on the next launch while the panel's full
 * live list is read again in the background.
 */
@Entity(tableName = "replay_channels", primaryKeys = ["accountKey", "streamId"])
data class ReplayChannelEntity(
    val accountKey: String,
    val streamId: String,
    val name: String,
    val streamIcon: String?,
    val categoryId: String?,
    val categoryName: String?,
    val archiveDays: Int?,
    val fetchedAt: Long
)

/**
 * One archived program, as get_simple_data_table returned it (title and
 * description still base64, `start` in the panel's local time - the
 * timeshift URL is built from it). Kept apart from epg_programs, whose
 * ended programmes are purged for the live "now playing" guide.
 */
@Entity(
    tableName = "replay_programs",
    primaryKeys = ["accountKey", "streamId", "startTs"],
    indices = [Index("stopTs")]
)
data class ReplayProgramEntity(
    val accountKey: String,
    val streamId: String,
    /** Seconds, as the panel sends them. */
    val startTs: Long,
    val stopTs: Long,
    val start: String?,
    val end: String?,
    val title: String?,
    val description: String?,
    val fetchedAt: Long
)
