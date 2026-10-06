package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vod_items",
    foreignKeys = [
        ForeignKey(
            entity = VodCategoryEntity::class,
            parentColumns = ["categoryId"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("categoryId")]
)
data class VodEntity(
    @PrimaryKey
    val streamId: String,
    val categoryId: String,
    val name: String,
    val plot: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: String = "",
    val year: String = "",
    val duration: String = ""
)
