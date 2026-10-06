package com.btv.ui.browse.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.IconButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.btv.ui.browse.ContentItem
import com.btv.ui.browse.ContentKind
import com.btv.ui.theme.BtvGreen
import com.btv.ui.theme.BtvGreenBright

@Composable
fun BackdropHeader(
    content: ContentItem?,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    showFavoriteButton: Boolean = true,
    onToggleFavorite: () -> Unit = {},
    showWatchedButton: Boolean = false,
    onToggleWatched: () -> Unit = {},
    showEpgButton: Boolean = false,
    onOpenEpg: () -> Unit = {},
    showRemoveFromHistoryButton: Boolean = false,
    onRemoveFromHistory: () -> Unit = {},
    actionsFocusRequester: FocusRequester = remember { FocusRequester() },
    onActionsFocused: () -> Unit = {},
    onActionsUp: () -> Unit = {},
    onActionsDown: () -> Unit = {}
) {
    var backdropAlpha by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo && content != null) {
        ContentInfoDialog(content = content, onDismiss = { showInfo = false })
    }

    LaunchedEffect(content?.id) {
        backdropAlpha = 0f
        backdropAlpha = 1f
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = backdropAlpha,
        animationSpec = tween(250)
    )

    // Tizen's .browse-synopsis/.synopsis-backdrop fall back to
    // var(--bg-surface) (#1a1a1a) when there's no image yet, not pure
    // black - a flat black panel mid-load reads as "broken", a dark grey
    // panel reads as "still loading".
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF1a1a1a))) {
        if (content != null) {
            // Backdrop image fills the entire allocated area
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(content.backdropUrl ?: content.posterUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = content.name,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1a1a1a)),
                contentScale = ContentScale.Crop,
                alpha = animatedAlpha
            )

            // Scrims: the text block sits on the left and over the lower part
            // of the artwork, so darken from the left as well as from the
            // bottom - a bright poster made the synopsis unreadable.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.88f),
                            0.45f to Color.Black.copy(alpha = 0.62f),
                            0.8f to Color.Black.copy(alpha = 0.15f),
                            1f to Color.Transparent
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.15f),
                            0.45f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black.copy(alpha = 0.96f)
                        )
                    )
            )

            // Action buttons (Tizen getFavZoneOptions order: star, watched,
            // remove), then the live guide. Left/Right walk the visible ones.
            val actions = buildList {
                if (showFavoriteButton) add(HeaderAction(
                    if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    if (isFavorite) "★" else "☆", isFavorite, onToggleFavorite
                ))
                if (showWatchedButton) add(HeaderAction(
                    when {
                        content.contentKind == ContentKind.SEASON && content.isWatched -> "Marquer la saison comme non vue"
                        content.contentKind == ContentKind.SEASON -> "Marquer la saison comme vue"
                        content.isWatched -> "Marquer comme non vu"
                        else -> "Marquer comme vu"
                    },
                    "✓", content.isWatched, onToggleWatched
                ))
                if (showRemoveFromHistoryButton) add(HeaderAction("Retirer de l'historique", "✕", false, onRemoveFromHistory))
                if (showEpgButton) add(HeaderAction("Guide TV", "EPG", false, onOpenEpg))
                if (content.hasDetails()) add(HeaderAction("Infos", "i", false) { showInfo = true })
            }
            val actionFocusRequesters = remember(actions.size) {
                List(actions.size) { index -> if (index == 0) actionsFocusRequester else FocusRequester() }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEachIndexed { index, action ->
                    if (index > 0) Spacer(Modifier.width(10.dp))
                    HeaderActionButton(
                        label = action.label,
                        symbol = action.symbol,
                        selected = action.selected,
                        focusRequester = actionFocusRequesters[index],
                        onFocused = onActionsFocused,
                        onUp = onActionsUp,
                        onDown = onActionsDown,
                        onLeft = { actionFocusRequesters.getOrNull(index - 1)?.requestFocus() },
                        onRight = { actionFocusRequesters.getOrNull(index + 1)?.requestFocus() },
                        onClick = action.onClick
                    )
                }
            }

            // Single consolidated bottom info panel (title + metadata) -
            // port of Tizen's .synopsis-card (style.css): generous padding
            // and full-size type, not squeezed down to fit - this panel has
            // a fixed, generous height (CategoryContent's 40% split) built
            // for exactly this amount of content.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 16.dp),
                // Text starts right under the search bar instead of being
                // stacked against the poster rail.
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = content.name,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = ReadableOnArtwork,
                    maxLines = 1,
                    // Clear of the action buttons at the top right.
                    modifier = Modifier.fillMaxWidth(0.78f),
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(8.dp))

                // "Now playing" EPG line + progress bar, live channels only -
                // set by BrowseViewModel's background per-channel enrichment
                // (port of Tizen's applyEpgToChannelRow, js/browse.js).
                if (content.epgProgress != null) {
                    if (!content.badge.isNullOrEmpty()) {
                        Text(
                            text = content.badge,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BtvGreenBright,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(content.epgProgress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(BtvGreenBright)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Meta row - exact port of Tizen's renderSynopsisMeta
                // (js/browse.js): "IMDb X.X" when the panel supplies a real
                // rating, else a 5-based rating rescaled to /10 and labelled
                // "Note" instead, then year/age/duration/genre/country.
                val metaParts = buildList {
                    if (!content.rating.isNullOrBlank()) add("IMDb ${content.rating}" to true)
                    if (!content.year.isNullOrBlank()) add(content.year to false)
                    if (!content.duration.isNullOrBlank()) add(content.duration to false)
                    if (!content.genre.isNullOrBlank()) add(content.genre to false)
                    if (!content.country.isNullOrBlank()) add(content.country to false)
                }
                if (metaParts.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        metaParts.forEachIndexed { index, (text, isRating) ->
                            if (index > 0) {
                                Text(text = "   ", fontSize = 15.sp, color = Color(0xFFDDDDDD))
                            }
                            Text(
                                text = text,
                                fontSize = 15.sp,
                                fontWeight = if (isRating) FontWeight.Bold else FontWeight.Normal,
                                color = if (isRating) BtvGreenBright else Color(0xFFEEEEEE),
                                style = ReadableOnArtwork
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (!content.plot.isNullOrEmpty()) {
                    Text(
                        text = content.plot,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = Color(0xFFF0F0F0),
                        style = ReadableOnArtwork,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        // weight(fill = false): measured after the title, meta and cast,
                        // with what is left - the full text is in the Infos dialog.
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(0.82f)
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Director + cast - plain text immediately (Xtream never
                // supplies photos), upgraded to TMDB photos if/when
                // fetchCastPhotosDebounced finds a match (BrowseViewModel).
                if (!content.director.isNullOrBlank()) {
                    Text(
                        text = "Réalisateur : ${content.director}",
                        fontSize = 13.sp,
                        color = Color(0xFFDDDDDD),
                        style = ReadableOnArtwork,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val photos = content.castPhotos
                if (!photos.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LazyRow {
                        items(photos) { person ->
                            CastAvatar(person, modifier = Modifier.width(72.dp).padding(end = 12.dp))
                        }
                    }
                } else if (!content.cast.isNullOrBlank()) {
                    Text(
                        text = "Cast : ${content.cast}",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Soft shadow so light text stays readable whatever the artwork behind it. */
private val ReadableOnArtwork = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.9f), offset = Offset(0f, 2f), blurRadius = 6f)
)

internal fun ContentItem.hasDetails(): Boolean =
    !plot.isNullOrBlank() || !cast.isNullOrBlank() || !director.isNullOrBlank() || !castPhotos.isNullOrEmpty()

/** TMDB photo in a circle; a generic silhouette when there is none, while it loads, or if it fails. */
@Composable
internal fun CastAvatar(person: com.btv.data.model.TmdbCastPerson, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            // requiredSize: never squeezed into an oval when space is tight.
            modifier = Modifier
                .requiredSize(size)
                .clip(CircleShape)
                .background(Color(0xFF3A3A3A))
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(com.btv.R.drawable.ic_lucide_user),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(size / 2)
            )
            if (person.photoUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(person.photoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = person.name,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = person.name,
            fontSize = 11.sp,
            color = Color(0xFFDDDDDD),
            style = ReadableOnArtwork,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class HeaderAction(val label: String, val symbol: String, val selected: Boolean, val onClick: () -> Unit)

@Composable
private fun HeaderActionButton(
    label: String,
    symbol: String,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onClick: () -> Unit,
    selected: Boolean = false,
    onLeft: () -> Unit = {},
    onRight: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.DirectionUp -> { onUp(); true }
                    Key.DirectionDown, Key.Back -> { onDown(); true }
                    Key.DirectionLeft -> { onLeft(); true }
                    Key.DirectionRight -> { onRight(); true }
                    else -> false // IconButton handles Center/Enter and accessibility clicks.
                }
            }
            .semantics { contentDescription = label }
            .background(if (focused) BtvGreen else Color.White.copy(alpha = 0.08f), CircleShape)
            .border(if (focused) 2.dp else 0.dp, Color.White, CircleShape)
    ) {
        Text(symbol, color = if (selected && !focused) BtvGreenBright else Color.White, fontSize = 18.sp)
    }
}
