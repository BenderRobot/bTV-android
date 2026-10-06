package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_programs",
    indices = [Index("channelId"), Index("startTime")]
)
data class EpgProgramEntity(
    @PrimaryKey val programId: String,
    val channelId: String,
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long,
    val genre: String = "",
    val rating: String = ""
)
