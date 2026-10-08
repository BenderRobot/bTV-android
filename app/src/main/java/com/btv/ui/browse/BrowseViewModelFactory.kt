package com.btv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository
import com.btv.data.store.PreferencesStore
import com.btv.domain.usecase.GetFavoritesUseCase
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase
import com.btv.domain.usecase.ToggleFavoriteUseCase

class BrowseViewModelFactory(
    private val authRepository: AuthRepository? = null,
    private val session: AuthSession? = null,
    private val preferencesStore: PreferencesStore? = null,
    private val getFavoritesUseCase: GetFavoritesUseCase? = null,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase? = null,
    private val getPlaybackProgressUseCase: GetPlaybackProgressUseCase? = null,
    private val getRecentlyWatchedUseCase: GetRecentlyWatchedUseCase? = null,
    private val showAllSnapshotStore: ShowAllSnapshotStore? = null,
    private val newEpisodesRepository: com.btv.data.repository.NewEpisodesRepository? = null,
    private val liveEpgDiskCache: com.btv.data.repository.LiveEpgDiskCache? = null,
    private val replayArchiveStore: com.btv.data.repository.ReplayArchiveStore? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BrowseViewModel(
            authRepository = authRepository,
            session = session,
            preferencesStore = preferencesStore,
            getFavoritesUseCase = getFavoritesUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            getPlaybackProgressUseCase = getPlaybackProgressUseCase,
            getRecentlyWatchedUseCase = getRecentlyWatchedUseCase,
            showAllSnapshotStore = showAllSnapshotStore,
            newEpisodesRepository = newEpisodesRepository,
            liveEpgDiskCache = liveEpgDiskCache,
            replayArchiveStore = replayArchiveStore
        ) as T
    }
}
