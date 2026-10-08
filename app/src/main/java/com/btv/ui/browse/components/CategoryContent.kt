package com.btv.ui.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.ui.browse.ContentItem
import com.btv.ui.components.BtvButton
import com.btv.ui.components.BtvButtonStyle
import com.btv.ui.components.BtvSearchField
import com.btv.ui.components.BtvSectionTitle
import com.btv.ui.components.countLabel
import com.btv.ui.theme.BtvTheme

private enum class ContentFocusZone {
    SEARCH, ACTIONS, RAIL
}

@Composable
fun CategoryContent(
    sectionTitle: String,
    contents: List<ContentItem>,
    selectedContent: ContentItem?,
    contentSearch: String,
    isFocused: Boolean,
    isSelectedFavorite: Boolean,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    onContentPreview: (String) -> Unit,
    onContentOpen: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onSearchCleared: () -> Unit,
    onToggleFavorite: () -> Unit,
    showFavoriteButton: Boolean = true,
    showWatchedButton: Boolean = false,
    onToggleWatched: () -> Unit = {},
    showEpgButton: Boolean = false,
    onOpenEpg: () -> Unit = {},
    showRemoveFromHistoryButton: Boolean = false,
    onRemoveFromHistory: () -> Unit = {},
    onFocusMiniPlayer: (() -> Unit)? = null,
    logoPosters: Boolean = false,
    /** "Tout afficher" shows only its first titles: say so instead of a false total. */
    isCapped: Boolean = false
) {
    // `contents` is already the search-filtered view - BrowseViewModel.
    // updateContentSearch re-filters from the never-capped full list, which
    // a purely client-side filter here couldn't do for "Tout afficher".
    var contentFocusZone by remember { mutableStateOf(ContentFocusZone.RAIL) }
    val searchFocusRequester = remember { FocusRequester() }
    val railFocusRequester = remember { FocusRequester() }
    val actionsFocusRequester = remember { FocusRequester() }
    // OK on a poster plays it: the hero only carries the secondary actions.
    val hasActions = selectedContent != null && (showFavoriteButton || showWatchedButton || showEpgButton)

    fun focusRail() {
        contentFocusZone = ContentFocusZone.RAIL
        railFocusRequester.requestFocus()
    }

    fun focusAboveRail() {
        contentFocusZone = if (hasActions) ContentFocusZone.ACTIONS else ContentFocusZone.SEARCH
        (if (hasActions) actionsFocusRequester else searchFocusRequester).requestFocus()
    }

    var railFocused by remember { mutableStateOf(false) }
    val colors = BtvTheme.colors

    LaunchedEffect(isFocused, contentFocusZone, hasActions) {
        if (isFocused) {
            val requester = when (contentFocusZone) {
                ContentFocusZone.SEARCH -> searchFocusRequester
                ContentFocusZone.ACTIONS -> if (hasActions) actionsFocusRequester else searchFocusRequester
                ContentFocusZone.RAIL -> railFocusRequester
            }
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
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBlack)
    ) {
        // Header: what is shown (and how many) on the left, search centred.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            BtvSectionTitle(
                title = com.btv.util.displayCategory(sectionTitle),
                subtitle = when {
                    contents.isEmpty() -> null
                    isCapped -> "${contents.size} premiers titres · la recherche couvre tout"
                    else -> countLabel(contents.size, "titre")
                },
                modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.28f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
            BtvSearchField(
                value = contentSearch,
                onValueChange = onSearchChanged,
                placeholder = "Rechercher dans cette catégorie",
                focusRequester = searchFocusRequester,
                modifier = Modifier.width(340.dp),
                onFocusChanged = { focused -> if (focused) contentFocusZone = ContentFocusZone.SEARCH },
                onDpadDown = {
                    if (hasActions) focusAboveRail() else focusRail()
                    true
                },
                onBack = {
                    if (contentSearch.isNotEmpty()) {
                        onSearchCleared()
                    } else {
                        contentFocusZone = ContentFocusZone.RAIL
                        try { railFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
                    }
                    true
                }
            )

            if (contentSearch.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                BtvButton(
                    text = null,
                    icon = com.btv.R.drawable.ic_lucide_x,
                    style = BtvButtonStyle.Ghost,
                    contentDescription = "Effacer la recherche",
                    onClick = onSearchCleared
                )
            }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(railFocusRequester)
                .onFocusChanged { focusState ->
                    railFocused = focusState.isFocused
                    if (focusState.isFocused) {
                        contentFocusZone = ContentFocusZone.RAIL
                    }
                }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp) {
                        focusAboveRail()
                        true
                    } else {
                        false
                    }
                }
                .focusable()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // The synopsis band takes whatever the rail does not need.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    BackdropHeader(
                        content = selectedContent,
                        isFavorite = isSelectedFavorite,
                        showFavoriteButton = showFavoriteButton,
                        onToggleFavorite = onToggleFavorite,
                        showWatchedButton = showWatchedButton,
                        onToggleWatched = onToggleWatched,
                        showEpgButton = showEpgButton,
                        onOpenEpg = onOpenEpg,
                        showRemoveFromHistoryButton = showRemoveFromHistoryButton,
                        onRemoveFromHistory = onRemoveFromHistory,
                        actionsFocusRequester = actionsFocusRequester,
                        onActionsFocused = { contentFocusZone = ContentFocusZone.ACTIONS },
                        onActionsUp = {
                            contentFocusZone = ContentFocusZone.SEARCH
                            searchFocusRequester.requestFocus()
                        },
                        onActionsDown = { focusRail() },
                        logoArtwork = logoPosters
                    )
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    ContentRail(
                        // The header above already names the category.
                        sectionTitle = "",
                        contents = contents,
                        selectedContentId = selectedContent?.id,
                        isFocused = railFocused,
                        isLoading = isLoading,
                        hasError = hasError,
                        onContentPreview = onContentPreview,
                        onContentOpen = onContentOpen,
                        onFocusMiniPlayer = onFocusMiniPlayer,
                        logoPosters = logoPosters
                    )
                }
            }
        }
    }
}
