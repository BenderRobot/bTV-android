package com.btv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.btv.data.model.AuthSession
import com.btv.ui.HomeViewModel
import com.btv.ui.theme.BtvTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HomeTile(val label: String, val icon: Int)
private enum class HomeFocusZone { Menu, Header }

/**
 * Exact port of Tizen's formatExpiry (js/app-shell.js): exp_date is Unix
 * seconds from the Xtream panel, not a hardcoded placeholder - "illimitée"
 * when the account has none, "(expiré)" for a past date, otherwise the
 * ceil()'d day count so a few hours left still reads as "1 jour" rather
 * than "0 jours".
 */
private fun formatExpiry(expDateSeconds: String?): String {
    val expSeconds = expDateSeconds?.toLongOrNull() ?: return "Expiration : illimitée"
    val expMs = expSeconds * 1000L
    val days = Math.ceil((expMs - System.currentTimeMillis()) / 86400000.0).toLong()
    val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(expMs))
    if (days < 0) return "Expiration : $dateStr (expiré)"
    return "Expiration : $dateStr ($days jour${if (days > 1) "s" else ""})"
}

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    session: AuthSession? = null,
    menuFocusRequester: FocusRequester? = null,
    miniPlayerFocusRequester: FocusRequester? = null,
    onOpenBrowse: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    val tiles = listOf(
        HomeTile("Favoris", com.btv.R.drawable.ic_lucide_star), HomeTile("En Direct", com.btv.R.drawable.ic_lucide_tv), HomeTile("Films", com.btv.R.drawable.ic_lucide_clapperboard),
        HomeTile("Séries", com.btv.R.drawable.ic_lucide_film), HomeTile("Rediffusion", com.btv.R.drawable.ic_lucide_rotate_ccw)
    )
    var selectedIndex by remember { mutableIntStateOf(2) }
    var headerIndex by remember { mutableIntStateOf(0) }
    var focusZone by remember { mutableStateOf(HomeFocusZone.Menu) }
    var showAccountDialog by remember { mutableStateOf(false) }
    val tileFocus = remember { List(tiles.size) { FocusRequester() } }
    val headerFocus = remember { List(3) { FocusRequester() } }

    LaunchedEffect(Unit) { tileFocus[selectedIndex].requestFocus() }

    fun openTile(index: Int) {
        when (index) {
            0 -> onOpenBrowse("favorites")
            1 -> onOpenBrowse("live")
            2 -> onOpenBrowse("movies")
            3 -> onOpenBrowse("series")
            4 -> onOpenBrowse("replay")
        }
    }

    Box(Modifier.fillMaxSize().background(BtvTheme.colors.bgBlack)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(com.btv.R.drawable.btv_icon).build(),
                        contentDescription = "bTV", modifier = Modifier.size(42.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("bTV", color = BtvTheme.colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeaderAction(com.btv.R.drawable.ic_lucide_user, focusZone == HomeFocusZone.Header && headerIndex == 0, headerFocus[0],
                        { focusZone = HomeFocusZone.Header; headerIndex = 0 }, {}, { headerIndex = 1; headerFocus[1].requestFocus() },
                        { focusZone = HomeFocusZone.Menu; tileFocus[selectedIndex].requestFocus() }, { showAccountDialog = true })
                    HeaderAction(com.btv.R.drawable.ic_lucide_refresh_cw, focusZone == HomeFocusZone.Header && headerIndex == 1, headerFocus[1],
                        { focusZone = HomeFocusZone.Header; headerIndex = 1 }, { headerIndex = 0; headerFocus[0].requestFocus() },
                        { headerIndex = 2; headerFocus[2].requestFocus() }, { focusZone = HomeFocusZone.Menu; tileFocus[selectedIndex].requestFocus() }, onRefresh)
                    HeaderAction(com.btv.R.drawable.ic_lucide_settings, focusZone == HomeFocusZone.Header && headerIndex == 2, headerFocus[2],
                        { focusZone = HomeFocusZone.Header; headerIndex = 2 }, { headerIndex = 1; headerFocus[1].requestFocus() }, {},
                        { focusZone = HomeFocusZone.Menu; tileFocus[selectedIndex].requestFocus() }, { onOpenSettings() })
                }
            }
            Text(
                formatExpiry(session?.userInfo?.exp_date), color = if (BtvTheme.colors.isLight) Color(0xFF0E5A4C) else Color(0xFF8EE4D4), fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .background(if (BtvTheme.colors.isLight) Color(0xFFD3EEF0) else Color(0xFF1A3344), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Row(
                Modifier.fillMaxWidth().align(Alignment.Center), Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                Alignment.CenterVertically
            ) {
                tiles.forEachIndexed { index, tile ->
                    val selected = index == selectedIndex
                    Column(
                        Modifier.width(116.dp).height(112.dp)
                            .then(if (index == selectedIndex && menuFocusRequester != null) Modifier.focusRequester(menuFocusRequester) else Modifier)
                            .focusRequester(tileFocus[index])
                            .onFocusChanged { if (it.hasFocus) { selectedIndex = index; focusZone = HomeFocusZone.Menu } }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> { val next = (index - 1).coerceAtLeast(0); selectedIndex = next; tileFocus[next].requestFocus(); true }
                                    Key.DirectionRight -> { val next = (index + 1).coerceAtMost(tiles.lastIndex); selectedIndex = next; tileFocus[next].requestFocus(); true }
                                    Key.DirectionUp -> { focusZone = HomeFocusZone.Header; headerFocus[headerIndex].requestFocus(); true }
                                    Key.DirectionDown -> {
                                        if (miniPlayerFocusRequester != null) {
                                            miniPlayerFocusRequester.requestFocus()
                                            true
                                        } else false
                                    }
                                    Key.Enter -> { openTile(index); true }
                                    else -> false
                                }
                            }
                            .clickable { selectedIndex = index; openTile(index) }
                            .border(if (selected) 2.dp else 1.dp, if (selected) BtvTheme.colors.textPrimary else BtvTheme.colors.textPrimary.copy(alpha = .45f), RoundedCornerShape(16.dp))
                            .background(if (selected) Color(0xFF17B355) else Color(0xFF1A3A52).copy(alpha = .72f), RoundedCornerShape(16.dp)),
                        Arrangement.Center, Alignment.CenterHorizontally
                    ) {
                        Icon(painter = painterResource(tile.icon), contentDescription = tile.label, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(tile.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
        if (showAccountDialog) TvDialog("Compte", { showAccountDialog = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Utilisateur : ${session?.userInfo?.username ?: "—"}")
                Text("Statut : ${session?.userInfo?.status ?: "—"}")
                Text("Expiration : ${formatExpiry(session?.userInfo?.exp_date).removePrefix("Expiration : ")}")
                Text("Connexions max : ${session?.userInfo?.max_connections?.toString() ?: "—"}")
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: Int, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit, left: () -> Unit, right: () -> Unit, down: () -> Unit, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).focusRequester(requester).onFocusChanged { if (it.hasFocus) onFocus() }.focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft -> { left(); true }; Key.DirectionRight -> { right(); true }
                    Key.DirectionDown -> { down(); true }; Key.Enter -> { onClick(); true }; else -> false
                }
            }.clickable(onClick = onClick).background(if (selected) BtvTheme.colors.accentTint else Color.Transparent, RoundedCornerShape(12.dp))
            .border(if (selected) 2.dp else 0.dp, BtvTheme.colors.accentOnSurface, RoundedCornerShape(12.dp)), Alignment.Center
    ) { Icon(painter = painterResource(icon), contentDescription = null, tint = BtvTheme.colors.textPrimary, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun TvDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = BtvTheme.colors.surface, contentColor = BtvTheme.colors.textSecondary, modifier = Modifier.width(480.dp).padding(16.dp)) {
            Column(Modifier.padding(22.dp), Arrangement.spacedBy(12.dp)) {
                Text(title, color = BtvTheme.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                content()
                Button(onClick = onDismiss, Modifier.align(Alignment.End)) { Text("Fermer") }
            }
        }
    }
}

private data class BrowseCategory(val id: String, val name: String, val system: Boolean, val count: Int? = null)
private data class BrowseItem(
    val title: String, val categoryId: String?, val category: String?, val imageUrl: String?, val synopsis: String?,
    val streamId: String, val type: String, val year: String? = null, val rating: String? = null, val archive: Boolean = false
)

@Composable
fun BrowseRoute(viewModel: HomeViewModel, initialType: String = "live", onBack: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val types = listOf("Favoris", "Live", "Films", "Séries", "Rediffusion")
    var typeIndex by remember { mutableIntStateOf(mapTypeIndex(initialType)) }
    var selectedCategory by remember { mutableStateOf("__all__") }
    var categoryQuery by remember { mutableStateOf("") }
    var contentQuery by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }

    val live = uiState.liveChannels.map { BrowseItem(it.name, it.categoryId, it.categoryName, it.streamIcon, null, it.streamId, "live", archive = it.tvArchive == 1) }
    val movies = uiState.vodItems.map { BrowseItem(it.name, it.categoryId, it.categoryName, it.movieImage ?: it.streamIcon, it.plot, it.streamId, "movie", it.year, it.rating) }
    val series = uiState.seriesItems.map { BrowseItem(it.title, it.categoryId, it.categoryName, it.cover, it.plot, it.seriesId, "series", it.year, it.rating) }
    val frenchPattern = Regex("(\\[FR\\]|\\|FR\\||\\bFR\\b)", RegexOption.IGNORE_CASE)
    val frenchMovies = movies.filter { frenchPattern.containsMatchIn(it.title) }
    val frenchSeries = series.filter { frenchPattern.containsMatchIn(it.title) }
    val items = when (typeIndex) {
        1 -> live
        2 -> frenchMovies
        3 -> frenchSeries
        4 -> live.filter { it.archive }
        else -> emptyList()
    }
    val sourceCategories = when (typeIndex) { 1 -> uiState.liveCategories; 2 -> uiState.movieCategories; 3 -> uiState.seriesCategories; else -> emptyList() }
        .filterNot { it.categoryName.contains("[AR]", ignoreCase = true) }
    val counts = items.groupingBy { it.categoryId }.eachCount()
    val categories = listOf(
        BrowseCategory("__continue__", "Continuer à regarder", true), BrowseCategory("__favorites__", "Favoris", true),
        BrowseCategory("__all__", "Tout afficher", true), BrowseCategory("__recentadded__", "Ajoutés récemment", true)
    ) + sourceCategories.map {
        BrowseCategory(it.categoryId, it.categoryName.replace(Regex("\\[(AR|EN)\\]\\s*", RegexOption.IGNORE_CASE), ""), false, counts[it.categoryId] ?: 0)
    }
        .filter { categoryQuery.isBlank() || it.name.contains(categoryQuery, true) }
    var focusedCategoryIndex by remember { mutableIntStateOf(0) }
    val categoryFocus = remember(categories) { List(categories.size) { FocusRequester() } }
    LaunchedEffect(categories) {
        if (categories.isNotEmpty()) {
            categoryFocus.getOrNull(focusedCategoryIndex.coerceIn(0, categories.lastIndex))?.requestFocus()
        }
    }
    val filtered = items.filter { (selectedCategory.startsWith("__") || it.categoryId == selectedCategory) && (contentQuery.isBlank() || it.title.contains(contentQuery, true)) }
    val selected = filtered.getOrNull(selectedIndex.coerceIn(0, (filtered.size - 1).coerceAtLeast(0)))
    val itemFocus = remember(filtered) { List(filtered.size) { FocusRequester() } }

    Row(Modifier.fillMaxSize().background(BtvTheme.colors.bgBlack).padding(18.dp), Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(260.dp).fillMaxHeight().background(BtvTheme.colors.surface).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onBack, modifier = Modifier.size(42.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("‹", fontSize = 25.sp) }
                Spacer(Modifier.width(10.dp)); Text(types[typeIndex], color = BtvTheme.colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(categoryQuery, { categoryQuery = it }, placeholder = { Text("Rechercher une catégorie...", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(categories) { index, category ->
                    val active = selectedCategory == category.id
                    Surface(
                        color = if (active) BtvTheme.colors.accentTint else Color.Transparent,
                        contentColor = BtvTheme.colors.textPrimary,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(categoryFocus[index])
                            .onFocusChanged { if (it.hasFocus) focusedCategoryIndex = index }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                    Key.DirectionUp -> {
                                        val next = (index - 1).coerceAtLeast(0)
                                        focusedCategoryIndex = next
                                        categoryFocus.getOrNull(next)?.requestFocus()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        val next = (index + 1).coerceAtMost(categories.lastIndex)
                                        focusedCategoryIndex = next
                                        categoryFocus.getOrNull(next)?.requestFocus()
                                        true
                                    }
                                    Key.Enter -> {
                                        selectedCategory = category.id
                                        selectedIndex = 0
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        itemFocus.getOrNull(0)?.requestFocus()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .clickable { selectedCategory = category.id; selectedIndex = 0 }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), Arrangement.SpaceBetween) {
                            Text(category.name, color = if (active || focusedCategoryIndex == index) BtvTheme.colors.textPrimary else BtvTheme.colors.textSecondary, fontSize = 13.sp, maxLines = 1)
                            category.count?.let { Text(it.toString(), color = BtvTheme.colors.textMuted, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                OutlinedTextField(contentQuery, { contentQuery = it; selectedIndex = 0 }, placeholder = { Text("Rechercher dans cette catégorie...", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.width(320.dp))
            }
            Spacer(Modifier.height(10.dp))
            BrowseHero(selected, uiState.isLoading, uiState.errorMessage)
            Spacer(Modifier.height(14.dp)); Text(if (typeIndex == 2 || typeIndex == 3) "${types[typeIndex]} [FR]" else types[typeIndex], color = BtvTheme.colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(filtered) { index, item ->
                    BrowseCard(
                        item = item,
                        selected = index == selectedIndex,
                        focusRequester = itemFocus[index],
                        onMoveLeft = { if (index > 0) itemFocus.getOrNull(index - 1)?.requestFocus() else categoryFocus.getOrNull(focusedCategoryIndex)?.requestFocus() },
                        onMoveRight = { if (index < filtered.lastIndex) itemFocus.getOrNull(index + 1)?.requestFocus() },
                        onClick = { selectedIndex = index }
                    )
                }
            }
        }
    }
}

private fun mapTypeIndex(type: String) = when (type.lowercase()) { "favorites" -> 0; "live" -> 1; "movies" -> 2; "series" -> 3; "replay" -> 4; else -> 1 }

@Composable
private fun BrowseHero(item: BrowseItem?, isLoading: Boolean, errorMessage: String?) {
    Surface(color = BtvTheme.colors.bgApp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(250.dp)) {
        if (item == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (isLoading) "Chargement du catalogue..." else errorMessage ?: "Aucun contenu disponible", color = BtvTheme.colors.textMuted) }
        else Box(Modifier.fillMaxSize()) {
            AsyncImage(item.imageUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .62f)))
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp).width(620.dp), Arrangement.spacedBy(7.dp)) {
                Text(item.title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(listOfNotNull(item.rating?.takeIf { it.isNotBlank() }?.let { "Note $it" }, item.year, item.category).joinToString("   "), color = Color(0xFF58E59C), fontSize = 13.sp)
                Text(item.synopsis ?: "Aucun synopsis disponible.", color = Color.White, fontSize = 14.sp, maxLines = 3)
            }
        }
    }
}

@Composable
private fun BrowseCard(
    item: BrowseItem,
    selected: Boolean,
    focusRequester: FocusRequester,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) BtvTheme.colors.accentTint else BtvTheme.colors.surface,
        contentColor = BtvTheme.colors.textPrimary,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .width(150.dp)
            .height(220.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.hasFocus) onClick() }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft -> { onMoveLeft(); true }
                    Key.DirectionRight -> { onMoveRight(); true }
                    Key.Enter -> { onClick(); true }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .border(if (selected) 2.dp else 1.dp, if (selected) (if (BtvTheme.colors.isLight) BtvTheme.colors.accentOnSurface else Color(0xFF36E28A)) else BtvTheme.colors.border, RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.padding(8.dp)) {
            AsyncImage(item.imageUrl, item.title, Modifier.fillMaxWidth().height(154.dp).background(BtvTheme.colors.surface2, RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.height(8.dp)); Text(item.title, color = BtvTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, fontSize = 14.sp); Text(item.category ?: item.type, color = BtvTheme.colors.textMuted, maxLines = 1, fontSize = 11.sp)
        }
    }
}

@Composable fun LoginRoute() { Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Text("Login") } }
@Composable fun PlayerRoute() { Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Text("Player") } }
