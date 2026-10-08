package com.btv.ui.browse.components

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.btv.ui.browse.ContentItem
import com.btv.ui.browse.LiveChannelGroup
import com.btv.ui.browse.EpgProgram
import com.btv.ui.theme.BtvGreen
import com.btv.ui.theme.BtvGreenBright
import com.btv.ui.components.BtvButton
import com.btv.ui.components.BtvOverline
import com.btv.ui.components.BtvSearchField
import com.btv.ui.components.BtvSectionTitle
import com.btv.ui.components.btvFocusSurface
import com.btv.ui.components.btvSelectionBar
import com.btv.ui.components.countLabel
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tizen's Live channel list and EPG panel, sharing the Browse sidebar. Each
 * row is a channel family (every quality folded together, see
 * LiveChannelGroups): OK plays its remembered quality, the panel's quality
 * buttons play a specific one.
 */
@Composable
fun LiveContent(
    sectionTitle: String,
    groups: List<LiveChannelGroup>,
    selectedChannel: ContentItem?,
    programs: List<EpgProgram>,
    isEpgLoading: Boolean,
    epgError: String? = null,
    onRetryEpg: () -> Unit = {},
    isLoading: Boolean,
    hasError: Boolean = false,
    contentSearch: String,
    isFocused: Boolean,
    favoriteIds: Set<Pair<String, String>>,
    onPreview: (String) -> Unit,
    onOpen: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onSearchCleared: () -> Unit,
    onToggleFavorite: (ContentItem) -> Unit,
    onOpenQuality: (LiveChannelGroup, ContentItem) -> Unit,
    onVisibleChannels: (List<List<String>>) -> Unit = {},
    canRemoveFromHistory: Boolean = false,
    onRemoveFromHistory: () -> Unit = {},
    onFocusMiniPlayer: (() -> Unit)? = null,
    onOpenGuide: (() -> Unit)? = null
) {
    val channelIds = groups.map { it.representative.id }
    val rowFocusRequesters = remember(channelIds) { channelIds.map { FocusRequester() } }
    val searchFocusRequester = remember { FocusRequester() }
    val favoriteFocusRequester = remember { FocusRequester() }
    val qualityFocusRequester = remember { FocusRequester() }
    val selectedGroup = groups.firstOrNull { it.contains(selectedChannel?.id) }
    fun LiveChannelGroup.isFavorite() = variants.any { favoriteIds.contains("LIVE" to it.id) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedIndex by remember(channelIds) {
        mutableIntStateOf(groups.indexOfFirst { it.contains(selectedChannel?.id) }.coerceAtLeast(0))
    }
    var searchFocused by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    fun focusRow(index: Int) {
        if (index !in groups.indices) return
        selectedIndex = index
        scope.launch {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
            repeat(10) {
                try {
                    rowFocusRequesters[index].requestFocus()
                    return@launch
                } catch (_: IllegalStateException) {
                    delay(30)
                }
            }
        }
    }

    // The guide follows what is on screen: rows a few ahead of the visible
    // window are asked too, so scrolling lands on filled rows. Keyed on ids,
    // not on the groups themselves - every applied guide line rebuilds them.
    val latestGroups by rememberUpdatedState(groups)
    val latestOnVisible by rememberUpdatedState(onVisibleChannels)
    LaunchedEffect(channelIds) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size }
            .distinctUntilChanged()
            .collectLatest { (first, visibleCount) ->
                delay(250) // let a fast scroll settle
                while (true) {
                    val rows = latestGroups
                    val end = (first + visibleCount.coerceAtLeast(6) + 4).coerceAtMost(rows.size)
                    val start = (first - 2).coerceIn(0, end)
                    latestOnVisible(rows.subList(start, end).map { group ->
                        listOf(group.representative.id) + group.variants.map { it.id }.filter { it != group.representative.id }
                    })
                    delay(60_000) // programmes change: roll the lines over
                }
            }
    }

    LaunchedEffect(isFocused, channelIds, searchFocused) {
        // Filtering changes channelIds after every typed character. Keep the
        // keyboard focused until the viewer explicitly leaves the search.
        if (isFocused && !searchFocused && groups.isNotEmpty()) {
            focusRow(selectedIndex.coerceIn(groups.indices))
        }
    }

    val colors = BtvTheme.colors
    Column(Modifier.fillMaxSize().background(colors.bgBlack).padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BtvSectionTitle(
                title = com.btv.util.displayCategory(sectionTitle),
                subtitle = if (groups.isNotEmpty()) countLabel(groups.size, "chaîne") else null,
                modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.28f)
            )
            BtvSearchField(
                value = contentSearch,
                onValueChange = onSearchChanged,
                placeholder = "Rechercher une chaîne",
                focusRequester = searchFocusRequester,
                modifier = Modifier.width(340.dp),
                onFocusChanged = { searchFocused = it },
                onDpadDown = { focusRow(selectedIndex); true },
                onBack = {
                    if (contentSearch.isNotEmpty()) onSearchCleared() else focusRow(selectedIndex)
                    true
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(0.92f).fillMaxHeight()) {
                when {
                    isLoading -> Text("Chargement des chaînes…", color = colors.textSecondary, style = BtvType.body)
                    hasError && groups.isEmpty() -> Unit
                    groups.isEmpty() -> Text("Aucune chaîne disponible.", color = colors.textSecondary, style = BtvType.body)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(BtvDimens.listSpacing)
                    ) {
                        itemsIndexed(groups, key = { _, group -> group.representative.id }) { index, group ->
                            var rowFocused by remember(group.representative.id) { mutableStateOf(false) }
                            LiveChannelRow(
                                group = group,
                                isFavorite = group.isFavorite(),
                                isFocused = rowFocused,
                                now = now,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(rowFocusRequesters[index])
                                    .onFocusChanged {
                                        rowFocused = it.isFocused
                                        if (it.isFocused) {
                                            selectedIndex = index
                                            onPreview(group.representative.id)
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                if (index == 0) searchFocusRequester.requestFocus() else focusRow(index - 1)
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                if (index == groups.lastIndex) onFocusMiniPlayer?.invoke()
                                                else focusRow(index + 1)
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                (if (group.hasQualities) qualityFocusRequester else favoriteFocusRequester).requestFocus()
                                                true
                                            }
                                            Key.DirectionCenter, Key.Enter -> { onOpen(group.launchVariant.id); true }
                                            else -> false
                                        }
                                    }
                                    .focusable()
                                    .clickable { onOpen(group.launchVariant.id) }
                            )
                        }
                    }
                }
            }
            LiveEpgPanel(
                channel = selectedGroup?.row ?: selectedChannel,
                group = selectedGroup,
                programs = programs,
                now = now,
                isLoading = isEpgLoading,
                error = epgError,
                isFavorite = selectedGroup?.isFavorite() ?: (selectedChannel?.let { favoriteIds.contains("LIVE" to it.id) } == true),
                favoriteFocusRequester = favoriteFocusRequester,
                qualityFocusRequester = qualityFocusRequester,
                onFocusChannels = { focusRow(selectedIndex) },
                // Un-favourite whichever sibling is the favourite; otherwise favourite the row's channel.
                onToggleFavorite = {
                    val target = selectedGroup?.let { group ->
                        group.variants.firstOrNull { favoriteIds.contains("LIVE" to it.id) } ?: group.representative
                    } ?: selectedChannel
                    target?.let(onToggleFavorite)
                },
                onOpenQuality = { variant -> selectedGroup?.let { onOpenQuality(it, variant) } },
                canRemoveFromHistory = canRemoveFromHistory,
                onRemoveFromHistory = onRemoveFromHistory,
                onRetry = onRetryEpg,
                onOpenGuide = onOpenGuide,
                modifier = Modifier.weight(1.4f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun LiveChannelRow(group: LiveChannelGroup, isFavorite: Boolean, isFocused: Boolean, now: Long, modifier: Modifier) {
    val channel = group.row
    val badgeParts = channel.badge?.split("  ", limit = 2).orEmpty()
    val progress = if (channel.epgStartTime != null && channel.epgEndTime != null &&
        channel.epgEndTime > channel.epgStartTime) {
        ((now - channel.epgStartTime).toFloat() / (channel.epgEndTime - channel.epgStartTime)).coerceIn(0f, 1f)
    } else channel.epgProgress ?: 0f
    val colors = BtvTheme.colors
    Column(
        modifier
            .btvFocusSurface(isFocused, shape = BtvShapes.card, restColor = colors.surface, focusedColor = colors.surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.posterUrl, 42)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Name and its tag take the free width; the star sits at the end.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    val name = com.btv.util.displayName(channel.name)
                    Text(name.title, Modifier.weight(1f, fill = false), color = colors.textPrimary, style = BtvType.title.copy(fontSize = 15.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    name.tags.firstOrNull()?.let {
                        Spacer(Modifier.width(8.dp))
                        com.btv.ui.components.BtvTag(it)
                    }
                    }
                    if (isFavorite) Text("★", color = colors.accentOnSurface, fontSize = 14.sp)
                }
                if (group.hasQualities) QualitySummary(group)
                if (badgeParts.isNotEmpty()) {
                    Text(badgeParts.first(), color = if (isFocused) colors.accentOnSurface else colors.textMuted, style = BtvType.meta.copy(fontSize = 12.sp), maxLines = 1)
                    Text(badgeParts.getOrNull(1).orEmpty(), color = colors.textSecondary, style = BtvType.meta,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("Programme non disponible", color = colors.textMuted, style = BtvType.meta)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            // Accent on the focused row only: eight green bars made green the page's colour.
            color = if (isFocused) colors.accentOnSurface else colors.textPrimary.copy(alpha = 0.35f),
            trackColor = colors.surface3
        )
    }
}

@Composable
private fun LiveEpgPanel(
    channel: ContentItem?,
    group: LiveChannelGroup?,
    programs: List<EpgProgram>,
    now: Long,
    isLoading: Boolean,
    error: String?,
    isFavorite: Boolean,
    favoriteFocusRequester: FocusRequester,
    qualityFocusRequester: FocusRequester,
    onFocusChannels: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQuality: (ContentItem) -> Unit,
    canRemoveFromHistory: Boolean,
    onRemoveFromHistory: () -> Unit,
    onRetry: () -> Unit,
    onOpenGuide: (() -> Unit)?,
    modifier: Modifier
) {
    val removeFocusRequester = remember { FocusRequester() }
    var retryFocused by remember { mutableStateOf(false) }
    val retryFocusRequester = remember { FocusRequester() }
    val guideFocusRequester = remember { FocusRequester() }
    val date = programs.firstOrNull()?.startTime?.let {
        SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(it))
    }.orEmpty()
    val colors = BtvTheme.colors
    Column(modifier.background(colors.surface, BtvShapes.panel).padding(22.dp)) {
        val hasQualities = group != null && group.hasQualities
        // Down from the actions: the quality buttons, else the guide's retry.
        fun actionsDown(): Boolean = when {
            hasQualities -> { qualityFocusRequester.requestFocus(); true }
            error != null -> { retryFocusRequester.requestFocus(); true }
            else -> false
        }
        var focusedAction by remember { mutableStateOf<String?>(null) }
        val favoriteLabel = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris"
        val removeLabel = "Retirer de l'historique"
        Row(verticalAlignment = Alignment.Top) {
            ChannelLogo(channel?.posterUrl, 56)
            Spacer(Modifier.width(14.dp))
            Text(channel?.name?.let { com.btv.util.displayTitle(it) } ?: "Sélectionner une chaîne", Modifier.weight(1f).padding(top = 12.dp), color = colors.textPrimary,
                style = BtvType.section, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (channel != null) {
                Spacer(Modifier.width(12.dp))
                // Same actions as the catalogue hero: icons, the focused one named underneath.
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BtvButton(
                            text = null,
                            icon = if (isFavorite) com.btv.R.drawable.ic_lucide_star_filled else com.btv.R.drawable.ic_lucide_star,
                            active = isFavorite,
                            contentDescription = favoriteLabel,
                            onClick = onToggleFavorite,
                            onFocusChanged = { focused ->
                                if (focused) focusedAction = favoriteLabel else if (focusedAction == favoriteLabel) focusedAction = null
                            },
                            modifier = Modifier
                                .focusRequester(favoriteFocusRequester)
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft, Key.Back -> { onFocusChannels(); true }
                                        Key.DirectionRight -> {
                                            if (canRemoveFromHistory) removeFocusRequester.requestFocus()
                                            else if (onOpenGuide != null) guideFocusRequester.requestFocus()
                                            true
                                        }
                                        Key.DirectionDown -> actionsDown()
                                        else -> false
                                    }
                                }
                        )
                        if (canRemoveFromHistory) {
                            BtvButton(
                                text = null,
                                icon = com.btv.R.drawable.ic_lucide_x,
                                contentDescription = removeLabel,
                                onClick = {
                                    onRemoveFromHistory()
                                    // The removed row's neighbour takes its place; go back to the list.
                                    onFocusChannels()
                                },
                                onFocusChanged = { focused ->
                                    if (focused) focusedAction = removeLabel else if (focusedAction == removeLabel) focusedAction = null
                                },
                                modifier = Modifier
                                    .focusRequester(removeFocusRequester)
                                    .onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                        when (event.key) {
                                            Key.DirectionLeft -> { favoriteFocusRequester.requestFocus(); true }
                                            Key.Back -> { onFocusChannels(); true }
                                            Key.DirectionRight -> {
                                                if (onOpenGuide != null) guideFocusRequester.requestFocus()
                                                true
                                            }
                                            Key.DirectionDown -> actionsDown()
                                            else -> false
                                        }
                                    }
                            )
                        }
                        if (onOpenGuide != null) {
                            val guideLabel = "Guide complet"
                            BtvButton(
                                text = null,
                                icon = com.btv.R.drawable.ic_lucide_calendar,
                                contentDescription = guideLabel,
                                onClick = onOpenGuide,
                                onFocusChanged = { focused ->
                                    if (focused) focusedAction = guideLabel else if (focusedAction == guideLabel) focusedAction = null
                                },
                                modifier = Modifier
                                    .focusRequester(guideFocusRequester)
                                    .onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                        when (event.key) {
                                            Key.DirectionLeft -> {
                                                (if (canRemoveFromHistory) removeFocusRequester else favoriteFocusRequester).requestFocus()
                                                true
                                            }
                                            Key.Back -> { onFocusChannels(); true }
                                            Key.DirectionRight -> true
                                            Key.DirectionDown -> actionsDown()
                                            else -> false
                                        }
                                    }
                            )
                        }
                    }
                    Text(
                        focusedAction.orEmpty(),
                        style = BtvType.meta,
                        color = colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (hasQualities && group != null) {
            QualityButtons(
                group = group,
                launchFocusRequester = qualityFocusRequester,
                onFocusChannels = onFocusChannels,
                onUp = { favoriteFocusRequester.requestFocus() },
                onDown = { if (error != null) retryFocusRequester.requestFocus() },
                onOpen = onOpenQuality
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(16.dp))
        if (error != null) {
            Column(
                Modifier.fillMaxWidth()
                    .focusRequester(retryFocusRequester)
                    .onFocusChanged { retryFocused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft, Key.Back -> { onFocusChannels(); true }
                            Key.DirectionUp -> {
                                (if (hasQualities) qualityFocusRequester else favoriteFocusRequester).requestFocus()
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> { onRetry(); true }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = "Réessayer le guide TV" }
                    .focusable()
                    .clickable { onRetry() }
                    .btvFocusSurface(retryFocused, restColor = colors.surface2, focusedColor = colors.surface3)
                    .padding(12.dp)
            ) {
                Text(error, color = colors.textPrimary, style = BtvType.meta)
                Text("Réessayer · OK", color = colors.accentOnSurface, style = BtvType.label)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (date.isNotEmpty()) BtvOverline(date)
        Spacer(Modifier.height(8.dp))
        when {
            channel == null -> Unit
            isLoading -> Text("Chargement du guide…", color = colors.textSecondary, style = BtvType.body)
            programs.isEmpty() && error == null -> Text("Programme non disponible.", color = colors.textSecondary, style = BtvType.body)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(programs.filter { it.endTime > now }, key = { it.id }) { program ->
                    val isNow = program.isCurrentlyAiring(now)
                    Row(
                        Modifier.fillMaxWidth()
                            .btvSelectionBar(isNow)
                            .background(if (isNow) colors.surface2 else Color.Transparent, BtvShapes.control)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "${formatLiveTime(program.startTime)}–${formatLiveTime(program.endTime)}",
                            Modifier.width(96.dp), color = if (isNow) colors.accentOnSurface else colors.textMuted, style = BtvType.meta
                        )
                        Column {
                            Text(program.title, color = if (isNow) colors.textPrimary else colors.textSecondary, style = BtvType.body,
                                fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal)
                            if (isNow) Text("EN COURS", color = colors.accentOnSurface, style = BtvType.overline)
                            // What is on now, when the provider describes it.
                            if (isNow && program.description.isNotBlank()) {
                                Text(
                                    program.description,
                                    color = colors.textSecondary,
                                    style = BtvType.meta,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "SD · HD · FHD · UHD" under the channel name, the quality OK will play highlighted. */
@Composable
private fun QualitySummary(group: LiveChannelGroup) {
    val colors = BtvTheme.colors
    val launchLabel = group.qualityLabels[group.variants.indexOf(group.launchVariant)].substringBefore(" · ")
    val labels = group.qualityLabels.map { it.substringBefore(" · ") }.distinct()
    Text(
        buildAnnotatedString {
            labels.forEachIndexed { index, label ->
                if (index > 0) append("  ")
                if (label == launchLabel) {
                    withStyle(SpanStyle(color = colors.accentOnSurface, fontWeight = FontWeight.Bold)) { append(label) }
                } else append(label)
            }
        },
        color = colors.textMuted, style = BtvType.meta.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

/**
 * One button per quality: OK plays that exact stream and makes it the
 * channel's remembered quality. Focus enters on the one OK would play.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityButtons(
    group: LiveChannelGroup,
    launchFocusRequester: FocusRequester,
    onFocusChannels: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onOpen: (ContentItem) -> Unit
) {
    val colors = BtvTheme.colors
    val requesters = remember(group.variants) { group.variants.map { FocusRequester() } }
    val launchIndex = group.variants.indexOf(group.launchVariant).coerceAtLeast(0)
    BtvOverline("Qualité")
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        group.variants.forEachIndexed { index, variant ->
            var focused by remember(variant.id) { mutableStateOf(false) }
            val isLaunch = index == launchIndex
            Text(
                (if (isLaunch) "▶ " else "") + group.qualityLabels[index],
                modifier = Modifier
                    .then(if (isLaunch) Modifier.focusRequester(launchFocusRequester) else Modifier)
                    .focusRequester(requesters[index])
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (index == 0) onFocusChannels() else requesters[index - 1].requestFocus()
                                true
                            }
                            Key.DirectionRight -> {
                                if (index < requesters.lastIndex) requesters[index + 1].requestFocus()
                                true
                            }
                            Key.Back -> { onFocusChannels(); true }
                            Key.DirectionDown -> { onDown(); true }
                            Key.DirectionUp -> { onUp(); true }
                            Key.DirectionCenter, Key.Enter -> { onOpen(variant); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .clickable { onOpen(variant) }
                    .btvFocusSurface(focused, restColor = colors.overlayMedium, focusedColor = colors.surface3, focusScale = BtvMotion.FOCUS_SCALE_SMALL)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                color = if (isLaunch) colors.accentOnSurface else colors.textPrimary,
                style = BtvType.label,
                fontWeight = if (isLaunch) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ChannelLogo(url: String?, size: Int) {
    val colors = BtvTheme.colors
    Box(Modifier.size(size.dp).background(com.btv.ui.theme.BtvLogoTile, BtvShapes.control).padding(5.dp),
        contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) Text("TV", color = colors.textMuted, style = BtvType.overline)
        else AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize())
    }
}

private fun formatLiveTime(time: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))
