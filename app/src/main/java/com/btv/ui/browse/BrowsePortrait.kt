package com.btv.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.btv.R
import com.btv.ui.components.BtvSearchField
import com.btv.ui.components.onTap
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

/**
 * Films / Séries on an upright phone: a top bar (back, title, category
 * picker), a search field and the posters in a grid. A film or a series
 * opens a details sheet (Lire / Saisons, favourite, watched); seasons and
 * episodes open straight away. Same ViewModel and actions as the TV layout.
 */
@Composable
internal fun BrowsePortrait(
    viewModel: BrowseViewModel,
    uiState: BrowseUiState,
    contents: List<ContentItem>,
    selection: ContentItem?,
    favoriteIds: Set<Pair<String, String>>,
    hasMiniPlayer: Boolean,
    onBack: () -> Unit,
    /**
     * Direct / Rediffusion: their own compact list (with its search) under
     * the top bar and the category picker, instead of the poster grid.
     */
    body: (@Composable () -> Unit)? = null
) {
    val colors = BtvTheme.colors
    val drilled = uiState.contentDrillStack.isNotEmpty()
    var showCategories by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<String?>(null) }

    // Inside a series, Back climbs one level (seasons, then the list).
    BackHandler(enabled = drilled) { viewModel.popContentDrill() }

    val categoryName = uiState.categories.firstOrNull { it.id == uiState.selectedCategoryId }?.name.orEmpty()
    // Episodes read better as a list (wide thumbnail, synopsis) than as posters.
    val episodeList = drilled && contents.isNotEmpty() && contents.all { it.contentKind == ContentKind.PLAYABLE }
    val searchFocus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bgBlack)
            .statusBarsPadding()
    ) {
        // Top bar.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).onTap { if (drilled) viewModel.popContentDrill() else onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_lucide_arrow_left), contentDescription = "Retour", tint = colors.textPrimary, modifier = Modifier.size(22.dp))
            }
            Text(
                com.btv.util.displayCategory(uiState.screenTitle),
                style = BtvType.section,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (!drilled) {
            // Category picker: the current one, a tap opens the full list.
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp))
                    .onTap { showCategories = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (uiState.contentType == ContentType.REPLAY) "Chaîne" else "Catégorie", style = BtvType.meta, color = colors.textMuted)
                Spacer(Modifier.width(10.dp))
                Text(
                    com.btv.util.displayCategory(categoryName).ifBlank { "Choisir" },
                    style = BtvType.body.copy(fontSize = 15.sp),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("▾", color = colors.textSecondary, fontSize = 16.sp)
            }
            if (body == null) {
                BtvSearchField(
                    value = uiState.contentSearch,
                    onValueChange = viewModel::updateContentSearch,
                    placeholder = "Rechercher dans cette catégorie",
                    focusRequester = searchFocus,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                )
            }
        }

        if (body != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // Room for the mini-player in the corner.
                    .padding(bottom = if (hasMiniPlayer) 124.dp else 0.dp)
            ) { body() }
        } else Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                uiState.error != null && contents.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(uiState.error, color = colors.textSecondary, style = BtvType.body)
                    Spacer(Modifier.height(12.dp))
                    PillButton("Réessayer", accent = true) { viewModel.retryCurrentLoad() }
                }
                uiState.isLoading && contents.isEmpty() ->
                    CircularProgressIndicator(color = colors.accentOnSurface, modifier = Modifier.align(Alignment.Center))
                contents.isEmpty() -> Text(
                    if (uiState.contentSearch.isNotBlank()) "Aucun résultat." else "Rien à afficher ici.",
                    color = colors.textMuted, style = BtvType.body, modifier = Modifier.align(Alignment.Center)
                )
                else -> {
                    val bottom = if (hasMiniPlayer) 140.dp else 24.dp
                    LazyVerticalGrid(
                        columns = if (episodeList) GridCells.Fixed(1) else GridCells.Adaptive(108.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottom),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(if (episodeList) 8.dp else 16.dp)
                    ) {
                        if (uiState.loadingProgress != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(uiState.loadingProgress, color = colors.textMuted, style = BtvType.meta)
                            }
                        }
                        items(contents, key = { it.id }) { item ->
                            if (episodeList) {
                                EpisodeRow(item) { viewModel.openContent(item.id) }
                            } else {
                                PosterTile(item) {
                                    when (item.contentKind) {
                                        // A season opens its episodes at once.
                                        ContentKind.SEASON -> viewModel.openContent(item.id)
                                        else -> {
                                            viewModel.previewContent(item.id)
                                            detailsFor = item.id
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCategories) {
        CategoryPicker(
            categories = uiState.categories,
            selectedId = uiState.selectedCategoryId,
            onPick = { id ->
                showCategories = false
                if (id != uiState.selectedCategoryId) viewModel.selectCategory(id)
            },
            onDismiss = { showCategories = false }
        )
    }

    val details = selection?.takeIf { it.id == detailsFor }
    if (details != null) {
        DetailsSheet(
            item = details,
            isFavorite = favoriteIds.contains(uiState.mediaType.name to details.id),
            canFavorite = details.canFavorite(uiState.mediaType),
            canMarkWatched = details.canMarkWatched(uiState.mediaType),
            onOpen = {
                detailsFor = null
                viewModel.openContent(details.id)
            },
            onToggleFavorite = { viewModel.toggleFavorite(details) },
            onToggleWatched = viewModel::toggleSelectedWatched,
            onDismiss = { detailsFor = null }
        )
    }
}

@Composable
private fun PosterTile(item: ContentItem, onClick: () -> Unit) {
    val colors = BtvTheme.colors
    Column(Modifier.onTap(action = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(BtvShapes.card)
                .background(colors.surface2)
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            item.badge?.let {
                Text(
                    it,
                    color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(com.btv.ui.theme.BtvGreenBright, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            if (item.isWatched) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(com.btv.ui.theme.BtvGreen, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            item.playbackProgress?.takeIf { it > 0f && !item.isWatched }?.let { progress ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.accentOnSurface))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            com.btv.util.displayTitle(item.name),
            color = colors.textPrimary,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EpisodeRow(item: ContentItem, onClick: () -> Unit) {
    val colors = BtvTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, BtvShapes.card)
            .onTap(action = onClick)
            .padding(8.dp)
    ) {
        Box(
            Modifier
                .width(132.dp)
                .aspectRatio(16f / 9f)
                .clip(BtvShapes.small)
                .background(colors.surface2)
        ) {
            AsyncImage(
                model = item.backdropUrl ?: item.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            item.playbackProgress?.takeIf { it > 0f && !item.isWatched }?.let { progress ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.accentOnSurface))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    com.btv.util.displayTitle(item.name),
                    color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (item.isWatched) Text("  ✓", color = colors.accentOnSurface, fontSize = 13.sp)
            }
            item.plot?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = colors.textMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Every category, with a search on top: the sidebar of the TV layout as a full-screen list. */
@Composable
private fun CategoryPicker(
    categories: List<BrowseCategory>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = BtvTheme.colors
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val shown = remember(categories, query) {
        if (query.isBlank()) categories
        else categories.filter { it.searchName.contains(query.trim(), ignoreCase = true) }
    }
    com.btv.ui.components.BtvOverlay(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.bgBlack)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).onTap(action = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_lucide_x), contentDescription = "Fermer", tint = colors.textPrimary, modifier = Modifier.size(22.dp))
                }
                Text("Catégories", style = BtvType.section, color = colors.textPrimary)
            }
            BtvSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Rechercher une catégorie",
                focusRequester = searchFocus,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            )
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                items(shown, key = { it.id }) { category ->
                    val selected = category.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (selected) colors.surface2 else Color.Transparent, RoundedCornerShape(10.dp))
                            .onTap { onPick(category.id) }
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selected) {
                            Box(Modifier.size(width = 3.dp, height = 18.dp).background(colors.accentOnSurface, RoundedCornerShape(2.dp)))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            com.btv.util.displayCategory(category.name),
                            color = if (category.isQuickAccess) colors.accentOnSurface else colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (category.isPinned) Text("★", color = colors.accentOnSurface, fontSize = 13.sp)
                        if (category.itemCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(category.itemCount.toString(), color = colors.textMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A film or a series: artwork, details, and what can be done with it. */
@Composable
private fun DetailsSheet(
    item: ContentItem,
    isFavorite: Boolean,
    canFavorite: Boolean,
    canMarkWatched: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = BtvTheme.colors
    // Sideways the artwork stays short: the details must fit the low screen.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    com.btv.ui.components.BtvOverlay(onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .onTap(action = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier
                    // Phone held sideways: a centred sheet, not the whole width.
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(if (landscape) 0.94f else 0.86f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(colors.surface)
                    .onTap { } // taps inside never close the sheet
                    .navigationBarsPadding()
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // Wide artwork fading into the sheet.
                    Box(if (landscape) Modifier.fillMaxWidth().height(150.dp) else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        AsyncImage(
                            model = item.backdropUrl ?: item.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(0.45f to Color.Transparent, 1f to colors.surface)
                            )
                        )
                    }
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        val name = com.btv.util.displayName(item.name)
                        Text(name.title, color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
                        val meta = listOfNotNull(
                            item.rating?.takeIf { it.isNotBlank() && it.toFloatOrNull() != 0f }?.let { "★ $it" },
                            item.year,
                            com.btv.util.displayDuration(item.duration),
                            item.genre
                        ).filter { it.isNotBlank() }
                        if (meta.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(meta.joinToString("  ·  "), color = colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                        item.plot?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(14.dp))
                            Text(it, color = colors.textPrimary.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 21.sp)
                        }
                        item.director?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(12.dp))
                            Text("Réalisation", color = colors.textMuted, fontSize = 12.sp)
                            Text(it, color = colors.textSecondary, fontSize = 14.sp)
                        }
                        val photos = item.castPhotos
                        if (!photos.isNullOrEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text("Casting", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(photos) { person ->
                                    com.btv.ui.browse.components.CastAvatar(person, modifier = Modifier.width(76.dp), size = 58.dp)
                                }
                            }
                        } else item.cast?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(12.dp))
                            Text("Avec", color = colors.textMuted, fontSize = 12.sp)
                            Text(it, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                // Actions, always visible at the bottom of the sheet.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillButton(
                        if (item.contentKind == ContentKind.SERIES) "Voir les saisons" else "Lire",
                        accent = true,
                        icon = if (item.contentKind == ContentKind.SERIES) R.drawable.ic_player_list else R.drawable.ic_player_play,
                        modifier = Modifier.weight(1f),
                        onClick = onOpen
                    )
                    if (canFavorite) {
                        RoundAction(if (isFavorite) "★" else "☆", if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris", highlighted = isFavorite, onClick = onToggleFavorite)
                    }
                    if (canMarkWatched) {
                        RoundAction("✓", if (item.isWatched) "Marquer comme non vu" else "Marquer comme vu", highlighted = item.isWatched, onClick = onToggleWatched)
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    accent: Boolean,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    onClick: () -> Unit
) {
    val colors = BtvTheme.colors
    Row(
        modifier
            .height(48.dp)
            .background(if (accent) colors.accentOnSurface else colors.surface2, RoundedCornerShape(24.dp))
            .onTap(action = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (accent) colors.onAccent else colors.textPrimary
        icon?.let {
            Icon(painterResource(it), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoundAction(symbol: String, label: String, highlighted: Boolean, onClick: () -> Unit) {
    val colors = BtvTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .background(colors.surface2, CircleShape)
            .border(1.dp, if (highlighted) colors.accentOnSurface else Color.Transparent, CircleShape)
            .onTap(action = onClick)
            .semanticsLabel(label),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = if (highlighted) colors.accentOnSurface else colors.textPrimary, fontSize = 20.sp)
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }
