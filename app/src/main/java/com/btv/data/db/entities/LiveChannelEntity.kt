package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "live_channels",
    foreignKeys = [
        ForeignKey(
            entity = LiveCategoryEntity::class,
            parentColumns = ["categoryId"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("categoryId")]
)
data class LiveChannelEntity(
    @PrimaryKey
    val streamId: String,
    val categoryId: String,
    val name: String,
    val logo: String = "",
    val streamUrl: String = ""
)
