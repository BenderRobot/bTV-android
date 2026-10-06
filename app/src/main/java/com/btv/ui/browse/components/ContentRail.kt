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
import com.btv.ui.theme.BtvTheme

@Composable
fun ContentRail(
    sectionTitle: String,
    contents: List<ContentItem>,
    selectedContentId: String?,
    isFocused: Boolean,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    onContentPreview: (String) -> Unit,
    onContentOpen: (String) -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val lazyListState = rememberLazyListState()
    val focusRequesters = remember(contents.size) { List(contents.size) { FocusRequester() } }
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

    // Only as tall as the posters and their zoom need: the rest of the
    // screen goes to the synopsis band above.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
    ) {
        if (sectionTitle.isNotEmpty()) {
            Text(
                text = sectionTitle,
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
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
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        LazyRow(
            state = lazyListState,
            // Fixed to the focused poster's height: the row never changes
            // size while a card grows, and every card stays vertically
            // centred on the same line.
            modifier = Modifier
                .fillMaxWidth()
                .height(POSTER_FOCUSED_HEIGHT + 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp, end = 24.dp, top = 10.dp, bottom = 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(contents) { index, content ->
                val isSelected = content.id == selectedContentId
                // Zoom follows the card's real focus. Deriving it from the rail's
                // "has focus" flag + selectedIndex made the card cycle through
                // zoom states while focus arrived from the sidebar (jitter).
                var cardHasFocus by remember { mutableStateOf(false) }

                ContentCard(
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
                        .focusable(),
                    onClick = {
                        selectedIndex = index
                        onContentOpen(content.id)
                    }
                )
            }
        }
    }
}

// 2:3 posters; the focused one is 25 % larger.
private val POSTER_WIDTH = 136.dp
private val POSTER_FOCUSED_WIDTH = 170.dp
private val POSTER_FOCUSED_HEIGHT = POSTER_FOCUSED_WIDTH * 1.5f

@Composable
private fun ContentCard(
    content: ContentItem,
    isSelected: Boolean,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    // Flat "cover flow": the focused poster really grows in the row's
    // layout (not a graphicsLayer scale drawn over its neighbours), so the
    // other posters slide aside and every card stays face-on.
    val posterWidth by animateDpAsState(
        targetValue = if (isFocused) POSTER_FOCUSED_WIDTH else POSTER_WIDTH,
        animationSpec = tween(180)
    )

    val colors = BtvTheme.colors
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            isFocused -> BtvGreenBright
            isSelected -> BtvGreen.copy(alpha = 0.45f)
            else -> Color.Transparent
        },
        animationSpec = tween(150)
    )

    Box(
        modifier = modifier
            .size(width = posterWidth, height = posterWidth * 1.5f)
            // The poster itself follows the rounded corners: its square
            // corners used to stick out of the border.
            .clip(RoundedCornerShape(10.dp))
            // Drawn over the image, hugging its edge; only the focused card has one.
            .border(
                width = if (isFocused) 5.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .background(colors.surface)
    ) {
        // Single full-bleed image, no separate reserved title strip below it
        // (that fixed dark box always showed, focused or not, looking like a
        // stray black bar under every poster) - the name is now an overlay
        // on the image itself, only when focused, same "peek preview" as before.
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(content.posterUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = content.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // Banners ("VOST", "4K") and titles sit at the top of IPTV
                // posters: when one is taller than the card, crop the bottom.
                alignment = Alignment.TopCenter
            )

            // Episode/season badge
            if (content.badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(BtvGreen, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = content.badge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Watched check (Tizen rail-poster-watched-icon)
            if (content.isWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(BtvGreen, RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "✓",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Progress bar overlay
            if (content.playbackProgress != null && content.playbackProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter)
                        .background(colors.surface3)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(content.playbackProgress)
                            .fillMaxHeight()
                            .background(BtvGreenBright)
                    )
                }
            }

            if (isFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = content.name,
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
