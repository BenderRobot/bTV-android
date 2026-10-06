package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "series_categories")
data class SeriesCategoryEntity(
    @PrimaryKey
    val categoryId: String,
    val categoryName: String,
    val categoryIndex: Int = 0
)
