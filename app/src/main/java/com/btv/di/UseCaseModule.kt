package com.btv.di

import com.btv.data.repository.FavoritesRepository
import com.btv.data.repository.HistoryRepository
import com.btv.data.repository.PlaybackProgressRepository
import com.btv.data.repository.SessionRepository
import com.btv.data.repository.EpgRepository
import com.btv.data.store.PreferencesStore
import com.btv.domain.usecase.GetFavoritesUseCase
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetPreferencesUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase
import com.btv.domain.usecase.SaveSessionUseCase
import com.btv.domain.usecase.ToggleFavoriteUseCase
import com.btv.domain.usecase.GetEpgUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Singleton
    @Provides
    fun provideGetFavoritesUseCase(repository: FavoritesRepository): GetFavoritesUseCase {
        return GetFavoritesUseCase(repository)
    }

    @Singleton
    @Provides
    fun provideToggleFavoriteUseCase(repository: FavoritesRepository): ToggleFavoriteUseCase {
        return ToggleFavoriteUseCase(repository)
    }

    @Singleton
    @Provides
    fun provideGetRecentlyWatchedUseCase(repository: HistoryRepository): GetRecentlyWatchedUseCase {
        return GetRecentlyWatchedUseCase(repository)
    }

    @Singleton
    @Provides
    fun provideGetPlaybackProgressUseCase(repository: PlaybackProgressRepository): GetPlaybackProgressUseCase {
        return GetPlaybackProgressUseCase(repository)
    }

    @Singleton
    @Provides
    fun provideSaveSessionUseCase(repository: SessionRepository): SaveSessionUseCase {
        return SaveSessionUseCase(repository)
    }

    @Singleton
    @Provides
    fun provideGetPreferencesUseCase(store: PreferencesStore): GetPreferencesUseCase {
        return GetPreferencesUseCase(store)
    }

    @Singleton
    @Provides
    fun provideGetEpgUseCase(repository: EpgRepository): GetEpgUseCase {
        return GetEpgUseCase(repository)
    }
}
