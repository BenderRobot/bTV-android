package com.btv.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.btv.ui.browse.components.CategorySidebar
import com.btv.ui.browse.components.CategoryContent
import com.btv.ui.browse.components.LiveContent
import com.btv.ui.browse.components.ReplayContent
import com.btv.ui.components.btvFocusSurface
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvType
import com.btv.ui.theme.BtvTheme

private enum class BrowseFocusZone {
    SIDEBAR, CONTENT
}

@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    contentType: ContentType = ContentType.VOD,
    externalContentFocusRequester: FocusRequester? = null,
    miniPlayerFocusRequester: FocusRequester? = null,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val pinPrompt by (viewModel.pinFlow?.prompt ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val liveQualityChoices by viewModel.liveQualityChoices.collectAsState()
    val liveGroups = androidx.compose.runtime.remember(uiState.contents, liveQualityChoices, favoriteIds, contentType) {
        if (contentType != ContentType.LIVE) emptyList()
        else groupLiveChannels(
            uiState.contents,
            liveQualityChoices,
            favoriteIds.filter { it.first == ContentType.LIVE.name }.mapTo(HashSet()) { it.second }
        )
    }

    LaunchedEffect(contentType) {
        viewModel.setContentType(contentType)
    }

    LaunchedEffect(contentType, uiState.selectedCategoryId, uiState.selectedContentId) {
        if (contentType == ContentType.LIVE) {
            uiState.selectedContentId?.let(viewModel::previewLiveEpg)
        } else {
            viewModel.loadSelectedDetails()
        }
    }

    val watchedIds by viewModel.watchedIds.collectAsState()
    val replayProgress by viewModel.replayProgress.collectAsState()
    val newEpisodeCounts by viewModel.newEpisodeCounts.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val displayedContents = remember(uiState.contents, watchedIds, newEpisodeCounts, uiState.mediaType) {
        uiState.contents.map { it.withWatchedState(uiState.mediaType, watchedIds).withNewEpisodesBadge(newEpisodeCounts) }
    }
    val displayedSelection = remember(uiState.selectedContent, watchedIds, newEpisodeCounts, uiState.mediaType) {
        uiState.selectedContent?.withWatchedState(uiState.mediaType, watchedIds)?.withNewEpisodesBadge(newEpisodeCounts)
    }

    var focusZone by remember {
        mutableStateOf(if (uiState.isSidebarVisible) BrowseFocusZone.SIDEBAR else BrowseFocusZone.CONTENT)
    }
    val sidebarFocusRequester = remember { FocusRequester() }
    val internalContentFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = externalContentFocusRequester ?: internalContentFocusRequester
    val retryFocusRequester = remember { FocusRequester() }

    var sidebarFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    var retryFocused by remember { mutableStateOf(false) }

    LaunchedEffect(focusZone, uiState.error, pinPrompt == null) {
        if (pinPrompt != null) return@LaunchedEffect
        val requester = when (focusZone) {
            BrowseFocusZone.SIDEBAR -> sidebarFocusRequester
            BrowseFocusZone.CONTENT -> if (uiState.error != null) retryFocusRequester else contentFocusRequester
        }
        // Retry until the target is actually placed in the layout - a single
        // attempt can lose the race against composition/layout on slower
        // devices, leaving nothing focused and D-Pad input dead forever.
        var attempts = 0
        while (attempts < 20) {
            try {
                requester.requestFocus()
                break
            } catch (e: IllegalStateException) {
                attempts++
                kotlinx.coroutines.delay(50)
            }
        }
    }

    fun handleKeyEvent(key: androidx.compose.ui.input.key.Key): Boolean {
        return when {
            key == Key.DirectionRight && focusZone == BrowseFocusZone.SIDEBAR -> {
                focusZone = BrowseFocusZone.CONTENT
                viewModel.setSidebarVisible(false)
                true
            }
            key == Key.DirectionLeft && focusZone == BrowseFocusZone.CONTENT -> {
                focusZone = BrowseFocusZone.SIDEBAR
                viewModel.setSidebarVisible(true)
                true
            }
            key == Key.Back && viewModel.popContentDrill() -> true
            // Back while browsing content (sidebar hidden) reveals the
            // sidebar first, same as pressing Left - it must not skip
            // straight past this layer to exiting the whole section.
            key == Key.Back && focusZone == BrowseFocusZone.CONTENT -> {
                focusZone = BrowseFocusZone.SIDEBAR
                viewModel.setSidebarVisible(true)
                true
            }
            // Only Back leaves the section; Left at the sidebar's edge stays put.
            key == Key.Back -> {
                onBack()
                true
            }
            key == Key.DirectionLeft && focusZone == BrowseFocusZone.SIDEBAR -> true
            else -> false
        }
    }

    val colors = BtvTheme.colors
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBlack)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    handleKeyEvent(keyEvent.key)
                } else {
                    false
                }
            }
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgBlack)
        ) {
            // Explicit fixed width instead of Row+weight: avoids relying on
            // weight() correctly propagating through an AnimatedVisibility-
            // wrapped sibling, which was leaving the sidebar taking 100% of
            // the screen width. Capped for the largest text sizes.
            val sidebarWidth = minOf(BtvDimens.sidebarWidth, maxWidth * 0.3f)

            Row(modifier = Modifier.fillMaxSize()) {
                if (uiState.isSidebarVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(sidebarWidth)
                            .focusRequester(sidebarFocusRequester)
                            .onFocusChanged { focusState ->
                                sidebarFocused = focusState.isFocused
                                if (focusState.isFocused) {
                                    focusZone = BrowseFocusZone.SIDEBAR
                                }
                            }
                            .focusable()
                    ) {
                        CategorySidebar(
                            title = uiState.screenTitle,
                            categories = uiState.categories,
                            selectedCategoryId = uiState.selectedCategoryId,
                            categorySearch = uiState.categorySearch,
                            isFocused = sidebarFocused,
                            onCategorySelected = { categoryId ->
                                viewModel.selectCategory(categoryId)
                            },
                            onSearchChanged = { query ->
                                viewModel.updateCategorySearch(query)
                            },
                            onBack = onBack,
                            canPin = viewModel.canPinCategories,
                            onTogglePin = viewModel::togglePinnedCategory,
                            onFocusMiniPlayer = miniPlayerFocusRequester?.let { requester ->
                                { requester.requestFocus() }
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .focusRequester(contentFocusRequester)
                        .onFocusChanged { focusState ->
                                contentFocused = focusState.hasFocus
                                if (focusState.hasFocus) {
                                focusZone = BrowseFocusZone.CONTENT
                            }
                        }
                        .focusable()
                ) {
                val currentCategoryName = uiState.categories
                    .find { it.id == uiState.selectedCategoryId }
                    ?.name
                    ?: ""

                if (contentType == ContentType.REPLAY) ReplayContent(
                    title = uiState.screenTitle,
                    archiveDays = uiState.replayArchiveDays,
                    programs = uiState.contents,
                    onAir = uiState.replayOnAir,
                    selected = uiState.selectedContent,
                    hasGuide = uiState.replayHasGuide,
                    isContinue = uiState.replayIsContinue,
                    isLoading = uiState.isLoading,
                    hasError = uiState.error != null,
                    contentSearch = uiState.contentSearch,
                    isFocused = contentFocused && !retryFocused,
                    progress = replayProgress,
                    watchedIds = watchedIds,
                    onPreview = viewModel::previewContent,
                    onOpen = viewModel::openContent,
                    onStartOver = viewModel::startOverReplay,
                    onMinuteTick = viewModel::onReplayMinuteTick,
                    onSearchChanged = viewModel::updateContentSearch,
                    onSearchCleared = viewModel::clearContentSearch,
                    onFocusMiniPlayer = miniPlayerFocusRequester?.let { requester ->
                        { requester.requestFocus() }
                    }
                ) else if (contentType == ContentType.LIVE) LiveContent(
                    sectionTitle = currentCategoryName,
                    groups = liveGroups,
                    selectedChannel = uiState.selectedContent,
                    programs = if (uiState.liveEpgChannelId == uiState.selectedContentId) uiState.liveEpgPrograms else emptyList(),
                    isEpgLoading = uiState.isLiveEpgLoading ||
                        (uiState.selectedContentId != null && uiState.liveEpgChannelId != uiState.selectedContentId),
                    epgError = if (uiState.liveEpgChannelId == uiState.selectedContentId) uiState.liveEpgError else null,
                    onRetryEpg = viewModel::retryLiveEpg,
                    isLoading = uiState.isLoading,
                    hasError = uiState.error != null,
                    contentSearch = uiState.contentSearch,
                    isFocused = contentFocused && !retryFocused,
                    favoriteIds = favoriteIds,
                    onPreview = viewModel::previewContent,
                    onOpen = viewModel::openContent,
                    onSearchChanged = viewModel::updateContentSearch,
                    onSearchCleared = viewModel::clearContentSearch,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onOpenQuality = { group, variant -> viewModel.openLiveQuality(group.key, variant) },
                    onVisibleChannels = viewModel::requestLiveEpg,
                    canRemoveFromHistory = uiState.canRemoveFromHistory,
                    onRemoveFromHistory = viewModel::removeSelectedFromHistory,
                    onFocusMiniPlayer = miniPlayerFocusRequester?.let { requester ->
                        { requester.requestFocus() }
                    }
                ) else CategoryContent(
                    sectionTitle = currentCategoryName,
                    contents = displayedContents,
                    selectedContent = displayedSelection,
                    isLoading = uiState.isLoading,
                    hasError = uiState.error != null,
                    contentSearch = uiState.contentSearch,
                    isFocused = contentFocused && !retryFocused,
                    isSelectedFavorite = uiState.selectedContent?.id?.let { favoriteIds.contains(uiState.mediaType.name to it) } ?: false,
                    onContentPreview = { contentId ->
                        viewModel.previewContent(contentId)
                    },
                    onContentOpen = { contentId ->
                        viewModel.openContent(contentId)
                    },
                    onSearchChanged = { query ->
                        viewModel.updateContentSearch(query)
                    },
                    onSearchCleared = {
                        viewModel.clearContentSearch()
                    },
                    onToggleFavorite = {
                        uiState.selectedContent?.let { viewModel.toggleFavorite(it) }
                    },
                    showFavoriteButton = uiState.selectedContent?.canFavorite(uiState.mediaType) == true,
                    showWatchedButton = uiState.selectedContent?.canMarkWatched(uiState.mediaType) == true,
                    onToggleWatched = viewModel::toggleSelectedWatched,
                    showEpgButton = uiState.mediaType == ContentType.LIVE,
                    showRemoveFromHistoryButton = uiState.canRemoveFromHistory,
                    onRemoveFromHistory = viewModel::removeSelectedFromHistory,
                    onOpenEpg = {
                        uiState.selectedContent?.let { viewModel.openEpg(it.id) }
                    },
                    onFocusMiniPlayer = miniPlayerFocusRequester?.let { requester ->
                        { requester.requestFocus() }
                    }
                )

                uiState.loadingProgress?.let { progress ->
                    Text(
                        progress,
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.BottomEnd)
                            .padding(18.dp)
                            .background(colors.surface2, BtvShapes.control)
                            .border(BtvDimens.hairline, colors.border, BtvShapes.control)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        color = colors.textSecondary,
                        style = BtvType.meta
                    )
                }

                uiState.error?.let { error ->
                    Column(
                        modifier = Modifier
                            .align(if (uiState.contents.isEmpty()) androidx.compose.ui.Alignment.Center else androidx.compose.ui.Alignment.BottomCenter)
                            .padding(24.dp)
                            .width(420.dp)
                            .btvFocusSurface(
                                focused = retryFocused,
                                shape = BtvShapes.panel,
                                restColor = colors.surface,
                                focusedColor = colors.surface2,
                                restBorder = colors.border
                            )
                            .semantics { contentDescription = "Réessayer le chargement" }
                            .focusRequester(retryFocusRequester)
                            .onFocusChanged { retryFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> { viewModel.retryCurrentLoad(); true }
                                    Key.DirectionDown -> {
                                        if (uiState.contents.isNotEmpty()) contentFocusRequester.requestFocus()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(error, color = colors.textPrimary, style = BtvType.body)
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text("Réessayer  ·  OK", color = colors.accentOnSurface, style = BtvType.label)
                    }
                }
            }
            }

            // Parental control: drawn over everything and owns the remote while open.
            pinPrompt?.let { prompt ->
                com.btv.ui.parental.PinDialog(
                    prompt = prompt,
                    onSubmit = { pin -> viewModel.pinFlow?.submit(pin) },
                    onCancel = { viewModel.pinFlow?.cancel() }
                )
            }
        }
    }
}
