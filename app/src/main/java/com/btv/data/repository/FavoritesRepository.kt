package com.btv.data.repository

import com.btv.data.db.dao.FavoritesDao
import com.btv.data.db.AccountScope
import com.btv.data.db.entities.FavoritesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

class FavoritesRepository(private val favoritesDao: FavoritesDao, private val accountScope: AccountScope = AccountScope.global) {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getAllFavorites(): Flow<List<FavoritesEntity>> = accountScope.key.flatMapLatest { key ->
        if (key == null) flowOf(emptyList()) else favoritesDao.getAllFavorites(key)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getFavoritesByType(type: String): Flow<List<FavoritesEntity>> =
        accountScope.key.flatMapLatest { key ->
            if (key == null) flowOf(emptyList()) else favoritesDao.getFavoritesByType(key, type)
        }

    suspend fun addFavorite(
        streamId: String,
        type: String,
        name: String,
        categoryId: String,
        categoryName: String,
        posterUrl: String? = null,
        containerExtension: String? = null
    ) {
        val favorite = FavoritesEntity(
            accountKey = accountScope.requireKey(),
            streamId = streamId,
            type = type,
            name = name,
            categoryId = categoryId,
            categoryName = categoryName,
            posterUrl = posterUrl,
            containerExtension = containerExtension
        )
        favoritesDao.insert(favorite)
    }

    suspend fun removeFavorite(streamId: String, type: String) {
        favoritesDao.deleteByStreamId(accountScope.requireKey(), type, streamId)
    }

    suspend fun isFavorite(streamId: String, type: String): Boolean {
        return favoritesDao.isFavorite(accountScope.requireKey(), type, streamId)
    }

    suspend fun getFavoritesCount(): Int {
        return favoritesDao.getFavoritesCount(accountScope.requireKey())
    }

    suspend fun getCategoriesWithFavorites(type: String): List<String> {
        return favoritesDao.getCategoriesWithFavorites(accountScope.requireKey(), type)
    }

    suspend fun clearAll() {
        favoritesDao.deleteAll(accountScope.requireKey())
    }
}
