package com.btv.ui.browse.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.btv.R
import com.btv.ui.browse.ContentItem
import com.btv.ui.browse.ContentKind
import com.btv.ui.components.BtvButton
import com.btv.ui.components.BtvButtonStyle
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

/**
 * The catalogue hero: the selected title's artwork bleeding in from the
 * right and fading into the page, its title, one line of metadata, three
 * lines of synopsis and the actions - "Regarder" first. The scrims only
 * exist to keep the text readable; there is no opaque panel.
 *
 * Actions, Left/Right walk them: Regarder, Ma liste (favorites), watched,
 * remove from history, live guide, details. Up goes to the search, Down /
 * Back to the posters.
 */
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
    onActionsDown: () -> Unit = {},
    onPlay: (() -> Unit)? = null
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
        animationSpec = tween(BtvMotion.NAV_MS),
        label = "heroArtwork"
    )

    val colors = BtvTheme.colors
    val page = colors.bgBlack
    Box(modifier = modifier.fillMaxSize()) {
        if (content == null) return@Box

        // Artwork on the right ~70%, faded into the page on its left and bottom edges.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.72f)
                .background(colors.surface)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(content.backdropUrl ?: content.posterUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = content.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // The middle of a poster identifies it; its top is often a
                // "MULTI" / "VOST" banner.
                alignment = Alignment.Center,
                alpha = animatedAlpha
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to page,
                            0.35f to page.copy(alpha = 0.85f),
                            0.7f to page.copy(alpha = 0.25f),
                            1f to Color.Transparent
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to page.copy(alpha = 0.45f),
                            0.3f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to page
                        )
                    )
            )
        }

        val textOnArtwork = if (colors.isLight) TextStyle.Default else ReadableOnArtwork
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxSize()
                .padding(start = 32.dp, end = 24.dp, top = 16.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = content.name,
                style = BtvType.hero.merge(textOnArtwork),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(TEXT_WIDTH)
            )

            Spacer(Modifier.height(8.dp))

            // "Now playing" EPG line + progress, live channels only - set by
            // BrowseViewModel's background per-channel enrichment (port of
            // Tizen's applyEpgToChannelRow, js/browse.js).
            if (content.epgProgress != null) {
                if (!content.badge.isNullOrEmpty()) {
                    Text(
                        text = content.badge,
                        style = BtvType.label.merge(textOnArtwork),
                        color = colors.accentOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(TEXT_WIDTH * 0.7f)
                        .height(3.dp)
                        .background(colors.textPrimary.copy(alpha = 0.2f), BtvShapes.small)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(content.epgProgress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(colors.accentOnSurface, BtvShapes.small)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Meta line - Tizen's renderSynopsisMeta order (js/browse.js):
            // rating, then year / duration / genre / country.
            val metaParts = listOfNotNull(
                content.year?.takeIf { it.isNotBlank() },
                content.duration?.takeIf { it.isNotBlank() },
                content.genre?.takeIf { it.isNotBlank() },
                content.country?.takeIf { it.isNotBlank() }
            )
            val rating = content.rating?.takeIf { it.isNotBlank() }
            if (rating != null || metaParts.isNotEmpty()) {
                Text(
                    text = buildAnnotatedString {
                        if (rating != null) {
                            withStyle(SpanStyle(color = colors.accentOnSurface, fontWeight = FontWeight.SemiBold)) {
                                append("IMDb $rating")
                            }
                            if (metaParts.isNotEmpty()) append("   ·   ")
                        }
                        append(metaParts.joinToString("   ·   "))
                    },
                    style = BtvType.meta.copy(fontSize = 13.sp).merge(textOnArtwork),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(TEXT_WIDTH)
                )
                Spacer(Modifier.height(10.dp))
            }

            if (!content.plot.isNullOrEmpty()) {
                Text(
                    text = content.plot,
                    style = BtvType.body.merge(textOnArtwork),
                    color = colors.textPrimary.copy(alpha = 0.82f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    // weight(fill = false): when space runs short (large text
                    // size) the synopsis gives way, never the buttons below.
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(TEXT_WIDTH)
                )
            }

            if (!content.director.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.textMuted)) { append("Réalisation  ") }
                        append(content.director)
                    },
                    style = BtvType.meta.copy(fontSize = 13.sp).merge(textOnArtwork),
                    color = colors.textPrimary.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(TEXT_WIDTH)
                )
            }

            // Cast under the synopsis: TMDB photos when found, else the panel's plain text.
            val photos = content.castPhotos
            if (!photos.isNullOrEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    photos.take(MAX_CAST).forEach { person ->
                        CastAvatar(person, modifier = Modifier.width(68.dp), size = 52.dp)
                    }
                }
            } else if (!content.cast.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Avec ${content.cast}",
                    style = BtvType.meta.merge(textOnArtwork),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(TEXT_WIDTH)
                )
            }

        }

        // Actions: a compact icon cluster in the top-right corner, the
        // focused one named underneath. Left/Right walk them, Up goes to the
        // search, Down / Back to the posters.
        val actions = buildList {
            if (onPlay != null) add(HeroAction(
                label = playLabel(content), icon = R.drawable.ic_lucide_play, primary = true, onClick = onPlay
            ))
            if (showFavoriteButton) add(HeroAction(
                label = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                icon = if (isFavorite) R.drawable.ic_lucide_star_filled else R.drawable.ic_lucide_star,
                active = isFavorite, onClick = onToggleFavorite
            ))
            if (showWatchedButton) add(HeroAction(
                label = when {
                    content.contentKind == ContentKind.SEASON && content.isWatched -> "Marquer la saison comme non vue"
                    content.contentKind == ContentKind.SEASON -> "Marquer la saison comme vue"
                    content.isWatched -> "Marquer comme non vu"
                    else -> "Marquer comme vu"
                },
                icon = R.drawable.ic_lucide_check, active = content.isWatched, onClick = onToggleWatched
            ))
            if (showRemoveFromHistoryButton) add(HeroAction(
                label = "Retirer de l'historique", icon = R.drawable.ic_lucide_x, onClick = onRemoveFromHistory
            ))
            if (showEpgButton) add(HeroAction(
                label = "Guide TV", icon = R.drawable.ic_lucide_calendar, onClick = onOpenEpg
            ))
            if (content.hasDetails()) add(HeroAction(
                label = "Infos", icon = R.drawable.ic_lucide_info
            ) { showInfo = true })
        }
        val actionFocusRequesters = remember(actions.size) {
            List(actions.size) { index -> if (index == 0) actionsFocusRequester else FocusRequester() }
        }
        var focusedAction by remember { mutableIntStateOf(-1) }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 32.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                actions.forEachIndexed { index, action ->
                    BtvButton(
                        text = null,
                        icon = action.icon,
                        style = if (action.primary) BtvButtonStyle.Primary else BtvButtonStyle.Secondary,
                        active = action.active,
                        contentDescription = action.label,
                        onClick = action.onClick,
                        onFocusChanged = { focused ->
                            if (focused) {
                                focusedAction = index
                                onActionsFocused()
                            } else if (focusedAction == index) {
                                focusedAction = -1
                            }
                        },
                        modifier = Modifier
                            .focusRequester(actionFocusRequesters[index])
                            .onKeyEvent {
                                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                    Key.DirectionUp -> { onActionsUp(); true }
                                    Key.DirectionDown, Key.Back -> { onActionsDown(); true }
                                    Key.DirectionLeft -> { actionFocusRequesters.getOrNull(index - 1)?.requestFocus(); true }
                                    Key.DirectionRight -> { actionFocusRequesters.getOrNull(index + 1)?.requestFocus(); true }
                                    else -> false
                                }
                            }
                    )
                }
            }
            // Always laid out (empty when nothing is focused): the cluster never jumps.
            Text(
                text = actions.getOrNull(focusedAction)?.label.orEmpty(),
                style = BtvType.meta.merge(textOnArtwork),
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** Cast photos shown in the hero; the Infos dialog lists everyone. */
private const val MAX_CAST = 6

/** Share of the hero width the text may use; the buttons get the full width. */
private const val TEXT_WIDTH = 0.6f

/** What OK on this title does, in words. */
private fun playLabel(content: ContentItem): String = when {
    content.contentKind == ContentKind.SERIES -> "Saisons"
    content.contentKind == ContentKind.SEASON -> "Épisodes"
    (content.playbackProgress ?: 0f) > 0f && !content.isWatched -> "Reprendre"
    else -> "Regarder"
}

/** Soft shadow so light text stays readable whatever the artwork behind it. */
private val ReadableOnArtwork = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 2f), blurRadius = 8f)
)

internal fun ContentItem.hasDetails(): Boolean =
    !plot.isNullOrBlank() || !cast.isNullOrBlank() || !director.isNullOrBlank() || !castPhotos.isNullOrEmpty()

/** TMDB photo in a circle; a generic silhouette when there is none, while it loads, or if it fails. */
@Composable
internal fun CastAvatar(person: com.btv.data.model.TmdbCastPerson, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val colors = BtvTheme.colors
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            // requiredSize: never squeezed into an oval when space is tight.
            modifier = Modifier
                .requiredSize(size)
                .clip(CircleShape)
                .background(colors.surface3)
                .border(1.dp, colors.border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_user),
                contentDescription = null,
                tint = colors.textMuted,
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
            style = BtvType.meta.copy(fontSize = 11.sp),
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class HeroAction(
    val label: String,
    val icon: Int,
    val primary: Boolean = false,
    val active: Boolean = false,
    val onClick: () -> Unit
)
