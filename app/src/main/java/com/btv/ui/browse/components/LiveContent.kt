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
import com.btv.ui.theme.BtvTheme
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
    onFocusMiniPlayer: (() -> Unit)? = null
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
    Column(Modifier.fillMaxSize().background(colors.bgBlack).padding(14.dp)) {
        CompactSearchField(
            value = contentSearch,
            onValueChange = onSearchChanged,
            placeholder = "Rechercher une chaîne...",
            focusRequester = searchFocusRequester,
            modifier = Modifier.fillMaxWidth(),
            onFocusChanged = { searchFocused = it },
            onDpadDown = { focusRow(selectedIndex); true },
            onBack = {
                if (contentSearch.isNotEmpty()) onSearchCleared() else focusRow(selectedIndex)
                true
            }
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(0.92f).fillMaxHeight()) {
                Text(sectionTitle, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                when {
                    isLoading -> Text("Chargement des chaînes…", color = colors.textSecondary)
                    hasError && groups.isEmpty() -> Unit
                    groups.isEmpty() -> Text("Aucune chaîne disponible.", color = colors.textSecondary)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
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
            .background(if (isFocused) colors.accentTint else colors.surface, RoundedCornerShape(10.dp))
            .border(2.dp, if (isFocused) BtvGreen else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.posterUrl, 42)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, Modifier.weight(1f), color = colors.textPrimary, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isFavorite) Text("★", color = colors.accentOnSurface, fontSize = 16.sp)
                }
                if (group.hasQualities) QualitySummary(group)
                if (badgeParts.isNotEmpty()) {
                    Text(badgeParts.first(), color = colors.accentOnSurface, fontSize = 10.sp, maxLines = 1)
                    Text(badgeParts.getOrNull(1).orEmpty(), color = colors.textSecondary, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("Programme non disponible", color = colors.textMuted, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = colors.accentOnSurface, trackColor = colors.surface3
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
    modifier: Modifier
) {
    var favoriteFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
    var removeFocused by remember { mutableStateOf(false) }
    val removeFocusRequester = remember { FocusRequester() }
    var retryFocused by remember { mutableStateOf(false) }
    val retryFocusRequester = remember { FocusRequester() }
    val date = programs.firstOrNull()?.startTime?.let {
        SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(it))
    }.orEmpty()
    val colors = BtvTheme.colors
    Column(modifier.background(colors.surface, RoundedCornerShape(14.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel?.posterUrl, 58)
            Spacer(Modifier.width(12.dp))
            Text(channel?.name ?: "Sélectionner une chaîne", Modifier.weight(1f), color = colors.textPrimary,
                fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        if (group != null && group.hasQualities) {
            QualityButtons(
                group = group,
                launchFocusRequester = qualityFocusRequester,
                onFocusChannels = onFocusChannels,
                onDown = { favoriteFocusRequester.requestFocus() },
                onOpen = onOpenQuality
            )
            Spacer(Modifier.height(10.dp))
        }
        if (channel != null) {
            Text(
                if (isFavorite) "★ Dans mes favoris" else "☆ Ajouter aux favoris",
                modifier = Modifier
                    .focusRequester(favoriteFocusRequester)
                    .onFocusChanged { favoriteFocused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft, Key.Back -> { onFocusChannels(); true }
                            Key.DirectionUp -> {
                                if (group != null && group.hasQualities) { qualityFocusRequester.requestFocus(); true } else false
                            }
                            Key.DirectionDown -> when {
                                canRemoveFromHistory -> { removeFocusRequester.requestFocus(); true }
                                error != null -> { retryFocusRequester.requestFocus(); true }
                                else -> false
                            }
                            Key.DirectionCenter, Key.Enter -> { onToggleFavorite(); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .clickable { onToggleFavorite() }
                    .background(if (favoriteFocused) colors.accentTint else colors.surface2, RoundedCornerShape(8.dp))
                    .border(2.dp, if (favoriteFocused) BtvGreen else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                color = if (isFavorite) colors.accentOnSurface else colors.textPrimary,
                fontSize = 13.sp
            )
            if (canRemoveFromHistory) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "✕ Retirer de l'historique",
                    modifier = Modifier
                        .focusRequester(removeFocusRequester)
                        .onFocusChanged { removeFocused = it.isFocused }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft, Key.Back -> { onFocusChannels(); true }
                                Key.DirectionUp -> { favoriteFocusRequester.requestFocus(); true }
                                Key.DirectionDown -> {
                                    if (error != null) { retryFocusRequester.requestFocus(); true } else false
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    onRemoveFromHistory()
                                    // The removed row's neighbour takes its place; go back to the list.
                                    onFocusChannels()
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusable()
                        .clickable { onRemoveFromHistory() }
                        .background(if (removeFocused) colors.accentTint else colors.surface2, RoundedCornerShape(8.dp))
                        .border(2.dp, if (removeFocused) BtvGreen else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    color = colors.textPrimary,
                    fontSize = 13.sp
                )
            }
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
                                (if (canRemoveFromHistory) removeFocusRequester else favoriteFocusRequester).requestFocus()
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> { onRetry(); true }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = "Réessayer le guide TV" }
                    .focusable()
                    .clickable { onRetry() }
                    .background(colors.surface2, RoundedCornerShape(8.dp))
                    .border(2.dp, if (retryFocused) BtvGreen else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(error, color = colors.textPrimary, fontSize = 12.sp)
                Text("Réessayer · OK", color = colors.accentOnSurface, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (date.isNotEmpty()) Text(date.uppercase(Locale.FRANCE), color = colors.textMuted, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when {
            channel == null -> Unit
            isLoading -> Text("Chargement du guide…", color = colors.textSecondary)
            programs.isEmpty() && error == null -> Text("Programme non disponible.", color = colors.textSecondary)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(programs, key = { it.id }) { program ->
                    val isNow = program.isCurrentlyAiring(now)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isNow) (if (colors.isLight) Color(0xFFE2F1E4) else Color(0xFF303830)) else Color.Transparent, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "${formatLiveTime(program.startTime)}–${formatLiveTime(program.endTime)}",
                            Modifier.width(100.dp), color = if (isNow) colors.accentOnSurface else colors.textMuted, fontSize = 11.sp
                        )
                        Column {
                            Text(program.title, color = colors.textPrimary, fontSize = 13.sp,
                                fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal)
                            if (isNow) Text("EN COURS", color = colors.accentOnSurface, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold)
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
        color = colors.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
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
    onDown: () -> Unit,
    onOpen: (ContentItem) -> Unit
) {
    val colors = BtvTheme.colors
    val requesters = remember(group.variants) { group.variants.map { FocusRequester() } }
    val launchIndex = group.variants.indexOf(group.launchVariant).coerceAtLeast(0)
    Text("QUALITÉ", color = colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
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
                            Key.DirectionUp -> true
                            Key.DirectionCenter, Key.Enter -> { onOpen(variant); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .clickable { onOpen(variant) }
                    .background(if (focused) colors.accentTint else colors.surface2, RoundedCornerShape(8.dp))
                    .border(2.dp, if (focused) BtvGreen else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (isLaunch) colors.accentOnSurface else colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = if (isLaunch) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ChannelLogo(url: String?, size: Int) {
    Box(Modifier.size(size.dp).background(Color(0xFF303030), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) Text("TV", color = Color.Gray, fontSize = 12.sp)
        else AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize())
    }
}

private fun formatLiveTime(time: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))
