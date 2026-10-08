package com.btv.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.btv.data.db.entities.FavoritesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoritesDao {

    /** Every row of the account, for the sync between devices. */
    @Query("SELECT * FROM favorites WHERE accountKey = :accountKey")
    suspend fun getAllForSync(accountKey: String): List<FavoritesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoritesEntity)

    @Update
    suspend fun update(favorite: FavoritesEntity)

    @Delete
    suspend fun delete(favorite: FavoritesEntity)

    @Query("DELETE FROM favorites WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId")
    suspend fun deleteByStreamId(accountKey: String, type: String, streamId: String)

    @Query("SELECT * FROM favorites WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId LIMIT 1")
    suspend fun getByStreamId(accountKey: String, type: String, streamId: String): FavoritesEntity?

    @Query("SELECT * FROM favorites WHERE accountKey = :accountKey ORDER BY addedAt DESC")
    fun getAllFavorites(accountKey: String): Flow<List<FavoritesEntity>>

    @Query("SELECT * FROM favorites WHERE accountKey = :accountKey AND type = :type ORDER BY addedAt DESC")
    fun getFavoritesByType(accountKey: String, type: String): Flow<List<FavoritesEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE accountKey = :accountKey AND type = :type AND streamId = :streamId)")
    suspend fun isFavorite(accountKey: String, type: String, streamId: String): Boolean

    @Query("SELECT COUNT(*) FROM favorites WHERE accountKey = :accountKey")
    suspend fun getFavoritesCount(accountKey: String): Int

    @Query("DELETE FROM favorites WHERE accountKey = :accountKey")
    suspend fun deleteAll(accountKey: String)

    @Query("SELECT DISTINCT categoryId FROM favorites WHERE accountKey = :accountKey AND type = :type")
    suspend fun getCategoriesWithFavorites(accountKey: String, type: String): List<String>
}
