package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "series_items",
    foreignKeys = [
        ForeignKey(
            entity = SeriesCategoryEntity::class,
            parentColumns = ["categoryId"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("categoryId")]
)
data class SeriesEntity(
    @PrimaryKey
    val streamId: String,
    val categoryId: String,
    val title: String,
    val plot: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: String = "",
    val year: String = "",
    val genre: String = "",
    val country: String = ""
)
