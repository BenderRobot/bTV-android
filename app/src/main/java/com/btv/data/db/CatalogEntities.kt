package com.btv.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "live_categories")
data class LiveCategoryEntity(
    @PrimaryKey val categoryId: String,
    val categoryName: String,
    val parentId: String?
)

@Entity(tableName = "live_channels")
data class LiveChannelEntity(
    @PrimaryKey val streamId: String,
    val name: String,
    val categoryId: String?,
    val categoryName: String?,
    val streamIcon: String?,
    val epgChannelId: String?,
    val tvArchive: Int?
)

@Entity(tableName = "vod_categories")
data class VodCategoryEntity(
    @PrimaryKey val categoryId: String,
    val categoryName: String,
    val parentId: String?
)

@Entity(tableName = "vod_items")
data class VodEntity(
    @PrimaryKey val streamId: String,
    val name: String,
    val categoryId: String?,
    val categoryName: String?,
    val streamIcon: String?,
    val movieImage: String?,
    val plot: String?,
    val rating: String?
)

@Entity(tableName = "series_categories")
data class SeriesCategoryEntity(
    @PrimaryKey val categoryId: String,
    val categoryName: String,
    val parentId: String?
)

@Entity(tableName = "series_items")
data class SeriesEntity(
    @PrimaryKey val seriesId: String,
    val title: String,
    val categoryId: String?,
    val categoryName: String?,
    val cover: String?,
    val plot: String?,
    val rating: String?
)
