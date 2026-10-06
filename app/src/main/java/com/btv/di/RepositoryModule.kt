package com.btv.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.btv.data.api.XtreamApi
import com.btv.data.db.BtvDatabase
import com.btv.data.db.AccountScope
import com.btv.data.repository.AuthRepository
import com.btv.data.repository.FavoritesRepository
import com.btv.data.repository.HistoryRepository
import com.btv.data.repository.PlaybackProgressRepository
import com.btv.data.repository.SessionRepository
import com.btv.data.repository.EpgRepository
import com.btv.data.store.CredentialsStore
import com.btv.data.store.PreferencesStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Singleton
    @Provides
    fun provideAccountScope(): AccountScope = AccountScope.global

    @Singleton
    @Provides
    fun provideBtvDatabase(@ApplicationContext context: Context): BtvDatabase {
        return BtvDatabase.getInstance(context)
    }

    @Singleton
    @Provides
    fun provideFavoritesRepository(database: BtvDatabase, accountScope: AccountScope): FavoritesRepository {
        return FavoritesRepository(database.favoritesDao(), accountScope)
    }

    @Singleton
    @Provides
    fun provideHistoryRepository(database: BtvDatabase, accountScope: AccountScope): HistoryRepository {
        return HistoryRepository(database.historyDao(), accountScope)
    }

    @Singleton
    @Provides
    fun providePlaybackProgressRepository(database: BtvDatabase, accountScope: AccountScope): PlaybackProgressRepository {
        return PlaybackProgressRepository(database.playbackProgressDao(), accountScope)
    }

    @Singleton
    @Provides
    fun provideSessionRepository(database: BtvDatabase): SessionRepository {
        return SessionRepository(database.userSessionDao())
    }

    @Singleton
    @Provides
    fun providePreferencesStore(@ApplicationContext context: Context): PreferencesStore {
        return PreferencesStore(context)
    }

    @Singleton
    @Provides
    fun provideCredentialsStore(@ApplicationContext context: Context): CredentialsStore {
        return CredentialsStore(context)
    }

    @Singleton
    @Provides
    fun provideAuthRepository(credentialsStore: CredentialsStore): AuthRepository {
        return AuthRepository(credentialsStore)
    }

    @Singleton
    @Provides
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).build()
    }

    @Singleton
    @Provides
    fun provideEpgRepository(database: BtvDatabase): EpgRepository {
        return EpgRepository(database.epgDao())
    }
}
