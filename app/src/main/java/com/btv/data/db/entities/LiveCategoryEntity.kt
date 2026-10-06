package com.btv.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "live_categories")
data class LiveCategoryEntity(
    @PrimaryKey
    val categoryId: String,
    val categoryName: String,
    val categoryIndex: Int = 0
)
