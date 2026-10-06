package com.btv.domain.usecase

import com.btv.data.repository.FavoritesRepository

class ToggleFavoriteUseCase(
    private val favoritesRepository: FavoritesRepository
) {

    suspend fun execute(
        streamId: String,
        type: String,
        name: String,
        categoryId: String,
        categoryName: String,
        posterUrl: String? = null,
        containerExtension: String? = null
    ): Boolean {
        val isFavorite = favoritesRepository.isFavorite(streamId, type)

        return if (isFavorite) {
            favoritesRepository.removeFavorite(streamId, type)
            false
        } else {
            favoritesRepository.addFavorite(
                streamId = streamId,
                type = type,
                name = name,
                categoryId = categoryId,
                categoryName = categoryName,
                posterUrl = posterUrl,
                containerExtension = containerExtension
            )
            true
        }
    }
}
