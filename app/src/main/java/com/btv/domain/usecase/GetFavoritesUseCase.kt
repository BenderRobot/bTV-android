package com.btv.domain.usecase

import com.btv.data.db.entities.FavoritesEntity
import com.btv.data.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow

class GetFavoritesUseCase(private val favoritesRepository: FavoritesRepository) {

    fun getAllFavorites(): Flow<List<FavoritesEntity>> {
        return favoritesRepository.getAllFavorites()
    }

    fun getFavoritesByType(type: String): Flow<List<FavoritesEntity>> {
        return favoritesRepository.getFavoritesByType(type)
    }

    suspend fun isFavorite(streamId: String, type: String): Boolean {
        return favoritesRepository.isFavorite(streamId, type)
    }

    suspend fun getFavoritesCount(): Int {
        return favoritesRepository.getFavoritesCount()
    }
}
