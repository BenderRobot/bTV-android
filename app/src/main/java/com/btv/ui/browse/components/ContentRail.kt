package com.btv.ui.browse.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.btv.ui.browse.ContentItem
import com.btv.ui.theme.BtvGreen
import com.btv.ui.theme.BtvGreenBright
import com.btv.ui.components.BtvPosterCard
import com.btv.ui.components.onTap
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

@Composable
fun ContentRail(
    sectionTitle: String,
    contents: List<ContentItem>,
    selectedContentId: String?,
    isFocused: Boolean,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    onContentPreview: (String) -> Unit,
    onContentOpen: (String) -> Unit,
    onFocusMiniPlayer: (() -> Unit)? = null,
    /** Channel logos (favourite channels): shown whole on the card. */
    logoPosters: Boolean = false
) {
    // Keyed on the list itself, not its size: opening a season replaces the
    // seasons by its episodes, and the position must start over there (on the
    // selected item - the first episode - not on "the 4th card" again).
    val contentIds = remember(contents) { contents.map { it.id } }
    var selectedIndex by remember(contentIds) {
        mutableIntStateOf(contents.indexOfFirst { it.id == selectedContentId }.coerceAtLeast(0))
    }
    val lazyListState = rememberLazyListState()
    val focusRequesters = remember(contentIds) { List(contents.size) { FocusRequester() } }
    var railHasFocus by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val colors = BtvTheme.colors

    // When the rail panel itself gains focus (via the outer FocusRequester
    // in CategoryContent), delegate actual focus down to the currently
    // selected card - the panel Box has no focusable of its own.
    LaunchedEffect(isFocused, focusRequesters) {
        if (isFocused && focusRequesters.isNotEmpty()) {
            val target = focusRequesters.getOrNull(selectedIndex) ?: focusRequesters[0]
            var attempts = 0
            while (attempts < 20) {
                try {
                    target.requestFocus()
                    break
                } catch (e: IllegalStateException) {
                    attempts++
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }

    // A new list (season opened, back to the seasons, new category): show it
    // from the selected item and, if a card had the focus, move it there.
    LaunchedEffect(contentIds) {
        if (contents.isEmpty()) return@LaunchedEffect
        try {
            lazyListState.scrollToItem(maxOf(0, selectedIndex - 2))
        } catch (e: IllegalStateException) {
        }
        if (railHasFocus) {
            val target = focusRequesters.getOrNull(selectedIndex) ?: return@LaunchedEffect
            repeat(20) {
                try {
                    target.requestFocus()
                    return@LaunchedEffect
                } catch (e: IllegalStateException) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }

    LaunchedEffect(selectedContentId, contents) {
        val index = contents.indexOfFirst { it.id == selectedContentId }
        // Only act if this is a genuinely new index (avoids a feedback loop:
        // onFocusChanged -> onContentSelected -> selectedContentId change ->
        // this effect -> requestFocus -> onFocusChanged again -> ...
        // which races with LazyRow layout placement and crashes).
        if (index >= 0 && index != selectedIndex) {
            selectedIndex = index
            coroutineScope.launch {
                try {
                    lazyListState.animateScrollToItem(
                        index = maxOf(0, index - 2),
                        scrollOffset = 0
                    )
                } catch (e: IllegalStateException) {
                }
            }
        }
    }

    // Only as tall as the posters, their titles and their lift need: the
    // rest of the screen goes to the hero above.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp)
    ) {
        if (sectionTitle.isNotEmpty()) {
            Text(
                text = sectionTitle,
                color = colors.textPrimary,
                style = BtvType.section,
                maxLines = 1,
                modifier = Modifier.padding(start = 32.dp)
            )
        }

        if (contents.isEmpty() && (isLoading || !hasError)) {
            // Port of Tizen's renderRailLoading (js/browse.js): plain
            // "Chargement..." text, same muted grey as its empty-state
            // message (.rail-empty/.rail-loading share one style in
            // style.css) - a blank rail while the fetch is in flight read
            // as broken, not "still loading".
            Text(
                text = if (isLoading) "Chargement..." else "Aucun contenu disponible.",
                color = colors.textMuted,
                style = BtvType.body,
                modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 24.dp)
            )
        }

        LazyRow(
            state = lazyListState,
            // Focus lifts a poster upwards from its base (see BtvPosterCard):
            // the top padding leaves it room, neighbours never move.
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { railHasFocus = it.hasFocus },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 32.dp, end = 32.dp, top = 14.dp, bottom = 4.dp
            ),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(BtvDimens.cardSpacing)
        ) {
            itemsIndexed(contents) { index, content ->
                val isSelected = content.id == selectedContentId
                // Zoom follows the card's real focus. Deriving it from the rail's
                // "has focus" flag + selectedIndex made the card cycle through
                // zoom states while focus arrived from the sidebar (jitter).
                var cardHasFocus by remember { mutableStateOf(false) }

                ContentCard(
                    logo = logoPosters,
                    content = content,
                    isSelected = isSelected,
                    isFocused = cardHasFocus,
                    // Size is set (and animated) by ContentCard itself.
                    modifier = Modifier
                        .focusRequester(focusRequesters[index])
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type != KeyEventType.KeyDown) false else when (keyEvent.key) {
                                // Left/Right only move focus - preview the card in the
                                // backdrop, same as Tizen's hover behaviour. Playback only
                                // starts on an explicit Center/Enter/click (see below):
                                // previously this called the same handler as "open", so
                                // simply arrow-browsing the rail launched the player.
                                Key.DirectionRight -> {
                                    if (selectedIndex < contents.size - 1) {
                                        selectedIndex++
                                        try { focusRequesters[selectedIndex].requestFocus() } catch (e: IllegalStateException) {}
                                        onContentPreview(contents[selectedIndex].id)
                                        coroutineScope.launch {
                                            try {
                                                lazyListState.animateScrollToItem(
                                                    index = maxOf(0, selectedIndex - 2),
                                                    scrollOffset = 0
                                                )
                                            } catch (e: IllegalStateException) {}
                                        }
                                    }
                                    true
                                }

                                Key.DirectionLeft -> {
                                    if (selectedIndex > 0) {
                                        selectedIndex--
                                        try { focusRequesters[selectedIndex].requestFocus() } catch (e: IllegalStateException) {}
                                        onContentPreview(contents[selectedIndex].id)
                                        coroutineScope.launch {
                                            try {
                                                lazyListState.animateScrollToItem(
                                                    index = maxOf(0, selectedIndex - 2),
                                                    scrollOffset = 0
                                                )
                                            } catch (e: IllegalStateException) {}
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }

                                Key.DirectionCenter, Key.Enter -> {
                                    onContentOpen(content.id)
                                    true
                                }

                                // The posters are the bottom row: Down reaches the mini-player.
                                Key.DirectionDown -> {
                                    onFocusMiniPlayer?.let { it(); true } ?: false
                                }

                                else -> false
                            }
                        }
                        // onFocusChanged must sit BEFORE focusable(): placed after it,
                        // it only observes focus targets further down the chain and
                        // never saw this card's own focus (no zoom, no border).
                        .onFocusChanged { focusState ->
                            cardHasFocus = focusState.isFocused
                            if (focusState.isFocused && !isSelected) {
                                selectedIndex = index
                                onContentPreview(content.id)
                            }
                        }
                        .focusable()
                        // Touch: the first tap shows the title in the hero, a second one opens it.
                        .onTap {
                            if (isSelected) onContentOpen(content.id)
                            else {
                                selectedIndex = index
                                onContentPreview(content.id)
                            }
                        },
                    onClick = {
                        selectedIndex = index
                        onContentOpen(content.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun ContentCard(
    logo: Boolean,
    content: ContentItem,
    isSelected: Boolean,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    BtvPosterCard(
        imageUrl = content.posterUrl,
        title = com.btv.util.displayTitle(content.name),
        language = com.btv.util.displayLanguage(content.name),
        focused = isFocused,
        badge = content.badge,
        isWatched = content.isWatched,
        progress = content.playbackProgress,
        logo = logo,
        // Above its neighbours while lifted.
        modifier = modifier.zIndex(if (isFocused) 1f else 0f)
    )
}
