package com.btv.ui.browse

import androidx.room.withTransaction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository
import com.btv.data.store.PreferencesStore
import com.btv.domain.usecase.GetFavoritesUseCase
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase
import com.btv.domain.usecase.ToggleFavoriteUseCase
import com.btv.ui.epg.EpgScreen
import com.btv.ui.player.PlayerLaunchRequest
import com.btv.ui.player.ZapItem
import java.io.File

@Composable
fun BrowseRoute(
    contentType: ContentType = ContentType.VOD,
    authRepository: AuthRepository? = null,
    session: AuthSession? = null,
    preferencesStore: PreferencesStore? = null,
    getFavoritesUseCase: GetFavoritesUseCase? = null,
    toggleFavoriteUseCase: ToggleFavoriteUseCase? = null,
    getPlaybackProgressUseCase: GetPlaybackProgressUseCase? = null,
    getRecentlyWatchedUseCase: GetRecentlyWatchedUseCase? = null,
    contentFocusRequester: FocusRequester? = null,
    miniPlayerFocusRequester: FocusRequester? = null,
    onOpenPlayer: (PlayerLaunchRequest) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val showAllSnapshotStore = remember(context) {
        ShowAllSnapshotStore(File(context.noBackupFilesDir, "show_all_index"))
    }
    val newEpisodesRepository = remember(context) {
        val database = com.btv.data.db.BtvDatabase.getInstance(context)
        com.btv.data.repository.NewEpisodesRepository(database.seriesEpisodeSnapshotDao(), database.favoritesDao())
    }
    val liveEpgDiskCache = remember(context) {
        com.btv.data.repository.LiveEpgDiskCache(com.btv.data.db.BtvDatabase.getInstance(context).epgDao())
    }
    val replayArchiveStore = remember(context) {
        val database = com.btv.data.db.BtvDatabase.getInstance(context)
        com.btv.data.repository.ReplayArchiveStore(database.replayDao()) { block -> database.withTransaction { block() } }
    }
    val viewModel: BrowseViewModel = viewModel(
        factory = BrowseViewModelFactory(
            authRepository, session, preferencesStore,
            getFavoritesUseCase, toggleFavoriteUseCase, getPlaybackProgressUseCase, getRecentlyWatchedUseCase,
            showAllSnapshotStore, newEpisodesRepository, liveEpgDiskCache, replayArchiveStore
        )
    )
    val selectedContent by viewModel.selectedContentItem.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // The player itself now lives in its own top-level route (see
    // MainActivity) so its ExoPlayer instance can survive navigating back
    // to Browse for the mini-player - BrowseRoute just hands off what to
    // play and immediately clears its own selection.
    LaunchedEffect(selectedContent) {
        val content = selectedContent ?: return@LaunchedEffect
        val streamUrl = content.streamUrl ?: return@LaunchedEffect
        // `this.` is required: the local `streamUrl` above would otherwise
        // shadow the item's own URL and every zap entry would replay `content`.
        fun ContentItem.toZap() = ZapItem(id, name, posterUrl, this.streamUrl)
        // Live: one zap entry per channel (on its remembered quality), and the
        // launched channel's siblings handed over for instant fallback.
        val liveGroups = if (uiState.mediaType == ContentType.LIVE) {
            groupLiveChannels(uiState.contents, viewModel.liveQualityChoices.value, emptySet())
        } else null
        // Parental control: zapping from a regular channel never lands on an adult one.
        val excluded = if (uiState.mediaType == ContentType.LIVE) viewModel.zapExclusions(content.id) else emptySet()
        val zapList = (liveGroups?.map { group -> (if (group.contains(content.id)) content else group.launchVariant).toZap() }
            ?: uiState.contents.map { it.toZap() }).filterNot { it.id in excluded }
        val liveVariants = if (uiState.mediaType == ContentType.REPLAY) viewModel.replayVariants(replayChannelIdOf(content.id))
            else liveGroups?.firstOrNull { it.contains(content.id) }?.variants?.map { it.toZap() }.orEmpty()
        onOpenPlayer(
            PlayerLaunchRequest(
                streamUrl = streamUrl,
                contentId = content.id,
                progressType = uiState.mediaType.name,
                contentName = content.name,
                zapList = zapList,
                posterUrl = content.posterUrl,
                categoryId = content.seriesId ?: uiState.selectedCategoryId.orEmpty(),
                categoryName = content.seriesName ?: uiState.categories.firstOrNull { it.id == uiState.selectedCategoryId }?.name.orEmpty(),
                seriesId = content.seriesId,
                seriesName = content.seriesName,
                seasonNum = content.seasonNum,
                liveVariants = liveVariants,
                explicitQuality = viewModel.consumeExplicitQualityLaunch()
            )
        )
        viewModel.clearSelection()
    }

    when {
        uiState.epgChannelId != null -> {
            val channelName = uiState.contents.find { it.id == uiState.epgChannelId }?.name
                ?: uiState.epgChannelId.orEmpty()
            EpgScreen(
                channelName = channelName,
                programs = uiState.epgPrograms,
                isLoading = uiState.isEpgLoading,
                error = uiState.epgError,
                onRetry = viewModel::retryEpg,
                onBack = { viewModel.closeEpg() }
            )
        }
        else -> {
            BrowseScreen(
                viewModel = viewModel,
                contentType = contentType,
                externalContentFocusRequester = contentFocusRequester,
                miniPlayerFocusRequester = miniPlayerFocusRequester,
                onBack = onBack
            )
        }
    }
}
