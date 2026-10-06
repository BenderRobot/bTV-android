package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vod_categories")
data class VodCategoryEntity(
    @PrimaryKey
    val categoryId: String,
    val categoryName: String,
    val categoryIndex: Int = 0
)
