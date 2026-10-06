package com.btv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.btv.data.db.entities.LiveCategoryEntity
import com.btv.data.db.entities.LiveChannelEntity
import com.btv.data.db.entities.VodCategoryEntity
import com.btv.data.db.entities.VodEntity
import com.btv.data.db.entities.SeriesCategoryEntity
import com.btv.data.db.entities.SeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveCategories(items: List<LiveCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveChannels(items: List<LiveChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVodCategories(items: List<VodCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVod(items: List<VodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesCategories(items: List<SeriesCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(items: List<SeriesEntity>)

    @Query("SELECT * FROM live_categories ORDER BY categoryName ASC")
    fun observeLiveCategories(): Flow<List<LiveCategoryEntity>>

    @Query("SELECT * FROM live_channels ORDER BY name ASC")
    fun observeLiveChannels(): Flow<List<LiveChannelEntity>>

    @Query("SELECT * FROM vod_categories ORDER BY categoryName ASC")
    fun observeVodCategories(): Flow<List<VodCategoryEntity>>

    @Query("SELECT * FROM vod_items ORDER BY name ASC")
    fun observeVod(): Flow<List<VodEntity>>

    @Query("SELECT * FROM series_categories ORDER BY categoryName ASC")
    fun observeSeriesCategories(): Flow<List<SeriesCategoryEntity>>

    @Query("SELECT * FROM series_items ORDER BY title ASC")
    fun observeSeries(): Flow<List<SeriesEntity>>
}
