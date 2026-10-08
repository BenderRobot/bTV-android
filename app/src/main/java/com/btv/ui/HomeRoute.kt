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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clip
import com.btv.ui.components.BtvOverline
import kotlinx.coroutines.launch
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
import com.btv.ui.components.BtvBrand
import com.btv.ui.components.BtvButton
import com.btv.ui.components.BtvButtonStyle
import com.btv.ui.components.BtvDialogSurface
import com.btv.ui.components.BtvDialogTitle
import com.btv.ui.components.BtvPosterCard
import com.btv.ui.components.btvFocusSurface
import com.btv.ui.components.requestFocusWithRetry
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HomeTile(val label: String, val description: String, val icon: Int, val type: String)
private enum class HomeFocusZone { Menu, Header, Continue }

private val HomeTiles = listOf(
    HomeTile("Rediffusion", "Programmes déjà diffusés", com.btv.R.drawable.ic_lucide_rotate_ccw, "replay"),
    HomeTile("En direct", "Chaînes TV en direct", com.btv.R.drawable.ic_lucide_tv, "live"),
    HomeTile("Favoris", "Vos chaînes, films et séries", com.btv.R.drawable.ic_lucide_star, "favorites"),
    HomeTile("Séries", "Séries et saisons", com.btv.R.drawable.ic_lucide_film, "series"),
    HomeTile("Films", "Catalogue de films", com.btv.R.drawable.ic_lucide_clapperboard, "movies")
)

/** Favoris, in the middle: the tile the remote lands on when the app opens. */
private const val DEFAULT_HOME_TILE = 2

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

/**
 * Home: a compact header (brand, subscription, account/refresh/settings)
 * over one headline and the five sections as compact cards. Remote: the
 * cards walk Left/Right, Up reaches the header, Down the mini-player.
 */
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    session: AuthSession? = null,
    menuFocusRequester: FocusRequester? = null,
    miniPlayerFocusRequester: FocusRequester? = null,
    onOpenBrowse: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRefresh: () -> Unit = {},
    continueItems: List<com.btv.ui.home.ContinueItem> = emptyList(),
    onPlayContinue: (com.btv.ui.home.ContinueItem) -> Unit = {}
) {
    val tiles = HomeTiles
    // Saveable, not remember: coming back from a section restores the focus
    // on that section's tile (the destination leaves composition meanwhile).
    var selectedIndex by rememberSaveable { mutableIntStateOf(DEFAULT_HOME_TILE) }
    var focusedTile by remember { mutableIntStateOf(-1) }
    var headerIndex by rememberSaveable { mutableIntStateOf(0) }
    // Saveable like the tile: back from the player, the focus returns to the card played.
    var focusZone by rememberSaveable { mutableStateOf(HomeFocusZone.Menu) }
    var continueIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedContinue by remember { mutableIntStateOf(-1) }
    val continueSlots = if (miniPlayerFocusRequester != null) 3 else 4
    val shownContinue = continueItems.take(continueSlots)
    val continueKeys = shownContinue.map { it.key }
    val continueFocus = remember(continueKeys) { continueKeys.map { FocusRequester() } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showAccountDialog by remember { mutableStateOf(false) }
    val tileFocus = remember { List(tiles.size) { FocusRequester() } }
    val headerFocus = remember { List(3) { FocusRequester() } }

    fun focusContinue(index: Int) {
        if (index !in continueFocus.indices) return
        continueIndex = index
        scope.launch { continueFocus[index].requestFocusWithRetry(attempts = 10, delayMs = 30) }
    }

    LaunchedEffect(Unit) { if (focusZone != HomeFocusZone.Continue) tileFocus[selectedIndex].requestFocusWithRetry() }
    // The row arrives a moment after the screen (database): land on it then.
    LaunchedEffect(shownContinue.isNotEmpty()) {
        if (focusZone != HomeFocusZone.Continue) return@LaunchedEffect
        if (shownContinue.isNotEmpty()) focusContinue(continueIndex.coerceIn(shownContinue.indices))
        else tileFocus[selectedIndex].requestFocusWithRetry()
    }

    fun openTile(index: Int) {
        tiles.getOrNull(index)?.let { onOpenBrowse(it.type) }
    }

    fun backToMenu() {
        focusZone = HomeFocusZone.Menu
        tileFocus[selectedIndex].requestFocus()
    }

    val colors = BtvTheme.colors
    val ambient = com.btv.ui.theme.BtvGreen.copy(alpha = if (colors.isLight) 0.05f else 0.07f)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bgBlack)
            // One static, barely-there wash of the accent in a corner: the page
            // stays black, just not flat.
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(ambient, Color.Transparent),
                        center = Offset(size.width * 0.92f, -size.height * 0.15f),
                        radius = size.width * 0.62f
                    )
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = BtvDimens.screenPaddingH, vertical = BtvDimens.screenPaddingV)
        ) {
            Row(
                Modifier.fillMaxWidth().height(BtvDimens.headerHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BtvBrand()
                Spacer(Modifier.weight(1f))
                // A newer version on GitHub: said here, and a dot on Réglages (where the button is).
                val updateStatus by com.btv.data.update.UpdateChecker.status.collectAsState()
                LaunchedEffect(Unit) { com.btv.data.update.UpdateChecker.check() }
                val update = updateStatus as? com.btv.data.update.UpdateStatus.Available
                if (update != null) {
                    UpdateAvailableLabel(onClick = onOpenSettings)
                    Spacer(Modifier.width(20.dp))
                }
                ExpiryLabel(formatExpiry(session?.userInfo?.exp_date))
                Spacer(Modifier.width(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderAction(
                        "Compte", com.btv.R.drawable.ic_lucide_user, headerFocus[0],
                        onFocus = { focusZone = HomeFocusZone.Header; headerIndex = 0 },
                        left = {}, right = { headerIndex = 1; headerFocus[1].requestFocus() },
                        down = ::backToMenu, onClick = { showAccountDialog = true }
                    )
                    HeaderAction(
                        "Actualiser", com.btv.R.drawable.ic_lucide_refresh_cw, headerFocus[1],
                        onFocus = { focusZone = HomeFocusZone.Header; headerIndex = 1 },
                        left = { headerIndex = 0; headerFocus[0].requestFocus() },
                        right = { headerIndex = 2; headerFocus[2].requestFocus() },
                        down = ::backToMenu, onClick = onRefresh
                    )
                    HeaderAction(
                        "Réglages", com.btv.R.drawable.ic_lucide_settings, headerFocus[2],
                        onFocus = { focusZone = HomeFocusZone.Header; headerIndex = 2 },
                        left = { headerIndex = 1; headerFocus[1].requestFocus() }, right = {},
                        down = ::backToMenu, onClick = { onOpenSettings() },
                        badge = update != null
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(BtvDimens.cardSpacing)) {
                tiles.forEachIndexed { index, tile ->
                    HomeCategoryCard(
                        tile = tile,
                        focused = focusedTile == index,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (index == selectedIndex && menuFocusRequester != null) Modifier.focusRequester(menuFocusRequester) else Modifier)
                            .focusRequester(tileFocus[index])
                            .onFocusChanged {
                                if (it.isFocused) focusedTile = index else if (focusedTile == index) focusedTile = -1
                                if (it.hasFocus) { selectedIndex = index; focusZone = HomeFocusZone.Menu }
                            }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> { val next = (index - 1).coerceAtLeast(0); selectedIndex = next; tileFocus[next].requestFocus(); true }
                                    Key.DirectionRight -> { val next = (index + 1).coerceAtMost(tiles.lastIndex); selectedIndex = next; tileFocus[next].requestFocus(); true }
                                    Key.DirectionUp -> { focusZone = HomeFocusZone.Header; headerFocus[headerIndex].requestFocus(); true }
                                    Key.DirectionDown -> when {
                                        shownContinue.isNotEmpty() -> {
                                            focusContinue(continueIndex.coerceIn(shownContinue.indices))
                                            true
                                        }
                                        miniPlayerFocusRequester != null -> {
                                            miniPlayerFocusRequester.requestFocus()
                                            true
                                        }
                                        else -> false
                                    }
                                    Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                                        if (event.nativeKeyEvent.repeatCount == 0) openTile(index)
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .pointerInput(index) { detectTapGestures { selectedIndex = index; openTile(index) } }
                    )
                }
            }
            Spacer(Modifier.weight(if (shownContinue.isNotEmpty()) 1f else 1.15f))
            // Bottom of the screen, compact: a few small cards, never more
            // than fits next to the mini-player.
            if (shownContinue.isNotEmpty()) {
                BtvOverline("Continuer à regarder")
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = if (miniPlayerFocusRequester != null) BtvDimens.miniPlayerWidth + 40.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    shownContinue.forEachIndexed { index, item ->
                        HomeContinueCard(
                            item = item,
                            focused = focusedContinue == index,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(continueFocus[index])
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focusedContinue = index
                                        continueIndex = index
                                        focusZone = HomeFocusZone.Continue
                                    } else if (focusedContinue == index) focusedContinue = -1
                                }
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft -> { focusContinue(index - 1); true }
                                        Key.DirectionRight -> { focusContinue(index + 1); true }
                                        Key.DirectionUp -> { focusZone = HomeFocusZone.Menu; tileFocus[selectedIndex].requestFocus(); true }
                                        Key.DirectionDown -> {
                                            miniPlayerFocusRequester?.requestFocus()
                                            true
                                        }
                                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                                            if (event.nativeKeyEvent.repeatCount == 0) onPlayContinue(item)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .pointerInput(item.key) { detectTapGestures { onPlayContinue(item) } }
                        )
                    }
                    // Keep card widths stable when there are fewer items than slots.
                    repeat(continueSlots - shownContinue.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (showAccountDialog) AccountDialog(session, formatExpiry(session?.userInfo?.exp_date)) { showAccountDialog = false }
    }
}

/** Small horizontal card: thumbnail, title, one quiet line and the progress. */
@Composable
private fun HomeContinueCard(item: com.btv.ui.home.ContinueItem, focused: Boolean, modifier: Modifier) {
    val colors = BtvTheme.colors
    val isChannel = item.kind == com.btv.ui.home.ContinueKind.LIVE || item.kind == com.btv.ui.home.ContinueKind.REPLAY
    Row(
        modifier
            .height(64.dp)
            .btvFocusSurface(
                focused = focused,
                shape = BtvShapes.card,
                restColor = colors.surface,
                focusedColor = colors.surface2,
                focusScale = BtvMotion.FOCUS_SCALE_SMALL
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .height(48.dp)
                .width(if (isChannel) 48.dp else 34.dp)
                .clip(BtvShapes.small)
                .background(if (isChannel) com.btv.ui.theme.BtvLogoTile else colors.surface3),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = null,
                contentScale = if (isChannel) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (isChannel) Modifier.padding(4.dp) else Modifier)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                com.btv.util.displayTitle(item.title),
                style = BtvType.title.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = if (focused) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            item.subtitle?.let {
                Text(com.btv.util.displayCategory(it), style = BtvType.meta.copy(fontSize = 12.sp), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.progress?.takeIf { it > 0f }?.let { progress ->
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(colors.surface3, BtvShapes.small)) {
                    Box(Modifier.fillMaxWidth(progress).height(2.dp).background(colors.accentOnSurface, BtvShapes.small))
                }
            }
        }
    }
}

/** Subscription end, discreet: a status dot and one muted line. */
@Composable
private fun ExpiryLabel(text: String) {
    val colors = BtvTheme.colors
    val expired = text.endsWith("(expiré)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(if (expired) com.btv.ui.theme.BtvDanger else colors.focusRing, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = BtvType.meta, color = if (expired) com.btv.ui.theme.BtvDanger else colors.textSecondary)
    }
}

@Composable
private fun HomeCategoryCard(tile: HomeTile, focused: Boolean, modifier: Modifier) {
    val colors = BtvTheme.colors
    Column(
        modifier
            .height(150.dp)
            .btvFocusSurface(
                focused = focused,
                shape = BtvShapes.panel,
                restColor = colors.surface,
                focusedColor = colors.surface2,
                focusScale = BtvMotion.FOCUS_SCALE
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(if (focused) colors.focusRing.copy(alpha = 0.16f) else colors.overlaySoft, BtvShapes.control),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(tile.icon),
                contentDescription = null,
                tint = if (focused) colors.accentOnSurface else colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Column {
            Text(tile.label, style = BtvType.title.copy(fontSize = 17.sp), color = colors.textPrimary, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                tile.description,
                style = BtvType.meta,
                color = if (focused) colors.textSecondary else colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeaderAction(
    label: String,
    icon: Int,
    requester: FocusRequester,
    onFocus: () -> Unit,
    left: () -> Unit,
    right: () -> Unit,
    down: () -> Unit,
    onClick: () -> Unit,
    /** A green dot on the icon: something waits there (a new version for Réglages). */
    badge: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    // The label hangs under the icon it names, outside the layout (no jump).
    Box(contentAlignment = Alignment.Center) {
    BtvButton(
        // Icon only; the label stays for accessibility.
        text = null,
        contentDescription = label,
        icon = icon,
        onFocusChanged = { isFocused -> focused = isFocused; if (isFocused) onFocus() },
        style = BtvButtonStyle.Ghost,
        onClick = onClick,
        modifier = Modifier
            .focusRequester(requester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft -> { left(); true }
                    Key.DirectionRight -> { right(); true }
                    Key.DirectionDown -> { down(); true }
                    else -> false
                }
            }
    )
    if (focused) {
        Text(
            label,
            style = BtvType.meta,
            color = BtvTheme.colors.textSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .wrapContentWidth(unbounded = true)
                .offset(y = 30.dp)
        )
    }
    if (badge) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-4).dp, y = 4.dp)
                .size(11.dp)
                .background(BtvTheme.colors.bgBlack, androidx.compose.foundation.shape.CircleShape)
                .padding(2.dp)
                .background(com.btv.ui.theme.BtvGreenBright, androidx.compose.foundation.shape.CircleShape)
        )
    }
    }
}

/**
 * "Mise à jour disponible" in the header, next to the expiry: the remote
 * reaches the button through Réglages (badged); a finger can tap this.
 */
@Composable
private fun UpdateAvailableLabel(onClick: () -> Unit) {
    val colors = BtvTheme.colors
    Row(
        modifier = Modifier
            .background(com.btv.ui.theme.BtvGreen.copy(alpha = 0.16f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .border(1.dp, com.btv.ui.theme.BtvGreen.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(com.btv.R.drawable.ic_lucide_refresh_cw),
            contentDescription = null,
            tint = com.btv.ui.theme.BtvGreenBright,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Mise à jour disponible", style = BtvType.meta, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AccountDialog(session: AuthSession?, expiry: String, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocus.requestFocusWithRetry() }
    Dialog(onDismissRequest = onDismiss) {
        BtvDialogSurface(maxWidth = 460.dp) {
            BtvDialogTitle("Compte")
            Spacer(Modifier.height(18.dp))
            AccountRow("Utilisateur", session?.userInfo?.username ?: "—")
            AccountRow("Statut", session?.userInfo?.status ?: "—")
            AccountRow("Expiration", expiry.removePrefix("Expiration : "))
            AccountRow("Connexions max", session?.userInfo?.max_connections?.toString() ?: "—")
            Spacer(Modifier.height(22.dp))
            BtvButton(
                text = "Fermer",
                onClick = onDismiss,
                style = BtvButtonStyle.Primary,
                modifier = Modifier.align(Alignment.End).focusRequester(closeFocus)
            )
        }
    }
}

@Composable
private fun AccountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = BtvType.body, color = BtvTheme.colors.textMuted, modifier = Modifier.width(150.dp))
        Text(value, style = BtvType.body, color = BtvTheme.colors.textPrimary)
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
        Column(Modifier.width(BtvDimens.sidebarWidth).fillMaxHeight().background(BtvTheme.colors.surface).padding(BtvDimens.sidebarPadding)) {
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
    Surface(color = BtvTheme.colors.surface, shape = BtvShapes.panel, modifier = Modifier.fillMaxWidth().height(250.dp)) {
        if (item == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (isLoading) "Chargement du catalogue..." else errorMessage ?: "Aucun contenu disponible", color = BtvTheme.colors.textMuted) }
        else Box(Modifier.fillMaxSize()) {
            AsyncImage(item.imageUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Scrim only where the text sits, not an opaque panel over the artwork.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .85f), 0.6f to Color.Black.copy(alpha = .35f), 1f to Color.Transparent)))
            Column(Modifier.align(Alignment.BottomStart).padding(24.dp).width(560.dp), Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = BtvType.hero, color = Color.White, maxLines = 2)
                Text(listOfNotNull(item.rating?.takeIf { it.isNotBlank() }?.let { "Note $it" }, item.year, item.category).joinToString("  ·  "), style = BtvType.meta, color = Color.White.copy(alpha = .75f))
                Text(item.synopsis ?: "Aucun synopsis disponible.", style = BtvType.body, color = Color.White.copy(alpha = .9f), maxLines = 3)
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
    BtvPosterCard(
        imageUrl = item.imageUrl,
        title = item.title,
        meta = item.category ?: item.type,
        focused = selected,
        modifier = Modifier
            .padding(top = 10.dp)
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
    )
}

@Composable fun LoginRoute() { Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Text("Login") } }
@Composable fun PlayerRoute() { Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Text("Player") } }
