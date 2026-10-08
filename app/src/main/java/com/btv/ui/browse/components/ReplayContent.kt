package com.btv.ui.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.btv.ui.browse.ContentItem
import com.btv.ui.browse.replayDayLabel
import com.btv.ui.browse.replayDayOf
import com.btv.ui.browse.replayDays
import com.btv.ui.theme.BtvGreen
import com.btv.ui.components.BtvSearchField
import com.btv.ui.components.BtvSectionTitle
import com.btv.ui.components.btvFocusSurface
import com.btv.ui.components.btvSelectionBar
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** A line of the program list: a finished program, or what is airing now. */
private sealed interface ReplayRow {
    val key: String
    data class Program(val item: ContentItem) : ReplayRow { override val key get() = item.id }
    data class OnAir(val item: ContentItem) : ReplayRow { override val key get() = "on_air" }
}

/**
 * Rediffusion's own layout, in place of the Films/Séries poster rail: day
 * tabs, the day's programs as a TV guide (oldest at the top, what is airing
 * now at the bottom, restartable from its beginning), and the focused
 * program's details on the right. Opens on the latest finished program.
 */
@Composable
fun ReplayContent(
    title: String,
    archiveDays: Int?,
    programs: List<ContentItem>,
    onAir: ContentItem?,
    selected: ContentItem?,
    hasGuide: Boolean,
    isContinue: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
    contentSearch: String,
    isFocused: Boolean,
    progress: Map<String, Float>,
    watchedIds: Set<String>,
    onPreview: (String) -> Unit,
    onOpen: (String) -> Unit,
    onStartOver: () -> Unit,
    onMinuteTick: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onSearchCleared: () -> Unit,
    onFocusMiniPlayer: (() -> Unit)? = null
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val latestTick by rememberUpdatedState(onMinuteTick)
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
            latestTick()
        }
    }

    val today = replayDayOf(now)
    val searching = contentSearch.isNotBlank()
    val days = remember(programs, onAir, isContinue, searching, today) {
        if (isContinue || searching) emptyList()
        else (replayDays(programs.mapNotNull { it.epgStartTime }) + listOfNotNull(onAir?.let { today })).distinct().sortedDescending()
    }
    var chosenDay by remember(title, isContinue) { mutableStateOf<LocalDate?>(null) }
    val day = chosenDay?.takeIf { it in days }
        ?: selected?.epgStartTime?.let(::replayDayOf)?.takeIf { it in days }
        ?: days.firstOrNull()
    val rows: List<ReplayRow> = remember(programs, onAir, day, days) {
        val shown = if (day == null) programs else programs.filter { it.epgStartTime?.let(::replayDayOf) == day }
        shown.map { ReplayRow.Program(it) } +
            listOfNotNull(onAir?.takeIf { day != null && day == today }?.let { ReplayRow.OnAir(it) })
    }
    val rowKeys = rows.map { it.key }
    val rowRequesters = remember(rowKeys) { rowKeys.map { FocusRequester() } }
    val tabRequesters = remember(days) { days.map { FocusRequester() } }
    val searchFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun defaultRowIndex(): Int {
        val selectedIndex = rows.indexOfFirst { it is ReplayRow.Program && it.item.id == selected?.id }
        if (selectedIndex >= 0) return selectedIndex
        // Latest finished program of the day, else the first line.
        return rows.indexOfLast { it is ReplayRow.Program }.takeIf { it >= 0 } ?: 0
    }

    var focusedIndex by remember(rowKeys) { mutableIntStateOf(defaultRowIndex()) }
    var searchFocused by remember { mutableStateOf(false) }
    var tabFocused by remember { mutableStateOf(false) }
    var onAirFocused by remember { mutableStateOf(false) }

    fun focusRow(index: Int) {
        if (index !in rows.indices) return
        focusedIndex = index
        scope.launch {
            listState.scrollToItem((index - 3).coerceAtLeast(0))
            repeat(10) {
                try {
                    rowRequesters[index].requestFocus()
                    return@launch
                } catch (_: IllegalStateException) {
                    delay(30)
                }
            }
        }
    }

    fun focusTab() {
        val index = days.indexOf(day)
        if (index < 0) focusRow(defaultRowIndex()) else try {
            tabRequesters[index].requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    fun selectDay(index: Int) {
        val target = days.getOrNull(index) ?: return
        chosenDay = target
        // The panel follows: the day's latest finished program.
        programs.lastOrNull { it.epgStartTime?.let(::replayDayOf) == target }?.let { onPreview(it.id) }
        try {
            tabRequesters[index].requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * Left/Right from the list: the previous/next day, landing on the
     * program closest to the same time of day, so days compare at a glance.
     * The list then refocuses on its own (rows rebuilt, see below).
     */
    fun changeDayFromList(index: Int, timeRef: Long?) {
        val target = days.getOrNull(index) ?: return
        chosenDay = target
        val dayPrograms = programs.filter { it.epgStartTime?.let(::replayDayOf) == target }
        val minuteOfDay = timeRef?.let { minuteOfDay(it) }
        val landing = if (minuteOfDay == null) dayPrograms.lastOrNull()
            else dayPrograms.minByOrNull { program -> kotlin.math.abs(minuteOfDay(program.epgStartTime ?: 0L) - minuteOfDay) }
        landing?.let { onPreview(it.id) }
    }

    LaunchedEffect(isFocused) {
        if (!isFocused) tabFocused = false
    }

    LaunchedEffect(isFocused, rowKeys, searchFocused, tabFocused) {
        // Changing day from a tab rebuilds the rows: leave the focus on the tab.
        if (isFocused && !searchFocused && !tabFocused && rows.isNotEmpty()) {
            focusRow(focusedIndex.coerceIn(rows.indices))
        }
    }

    val colors = BtvTheme.colors
    Column(Modifier.fillMaxSize().background(colors.bgBlack).padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BtvSectionTitle(
                title = com.btv.util.displayCategory(title),
                subtitle = listOfNotNull(
                    archiveDays?.let { "Archive $it j" },
                    if (days.size > 1) "◀ ▶ changer de jour" else null
                ).joinToString("  ·  "),
                modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.28f)
            )
            BtvSearchField(
                value = contentSearch,
                onValueChange = onSearchChanged,
                placeholder = "Rechercher un programme",
                focusRequester = searchFocusRequester,
                modifier = Modifier.width(340.dp),
                onFocusChanged = { focused ->
                    searchFocused = focused
                    if (focused) tabFocused = false
                },
                onDpadDown = { if (days.isNotEmpty()) focusTab() else focusRow(focusedIndex); true },
                onBack = {
                    if (contentSearch.isNotEmpty()) onSearchCleared() else focusRow(focusedIndex)
                    true
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.05f).fillMaxHeight()) {
                if (days.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        days.forEachIndexed { index, tabDay ->
                            var focused by remember(tabDay) { mutableStateOf(false) }
                            val isCurrent = tabDay == day
                            Text(
                                replayDayLabel(tabDay, today),
                                modifier = Modifier
                                    .focusRequester(tabRequesters[index])
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        // Cleared by whatever takes the focus next (row, search,
                                        // leaving the zone) - not here, or moving from one tab to
                                        // the next would let the rebuilt list grab the focus.
                                        if (it.isFocused) tabFocused = true
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                        when (event.key) {
                                            Key.DirectionLeft -> if (index > 0) { selectDay(index - 1); true } else false
                                            Key.DirectionRight -> { if (index < days.lastIndex) selectDay(index + 1); true }
                                            Key.DirectionUp -> { searchFocusRequester.requestFocus(); true }
                                            Key.DirectionDown, Key.DirectionCenter, Key.Enter -> {
                                                tabFocused = false
                                                focusRow(defaultRowIndex())
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .focusable()
                                    .clickable { selectDay(index) }
                                    .btvFocusSurface(
                                        focused,
                                        shape = BtvShapes.pill,
                                        restColor = if (isCurrent) colors.surface3 else colors.surface,
                                        focusedColor = colors.surface3,
                                        focusScale = BtvMotion.FOCUS_SCALE_SMALL
                                    )
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                color = when {
                                    isCurrent -> colors.accentOnSurface
                                    focused -> colors.textPrimary
                                    else -> colors.textSecondary
                                },
                                style = BtvType.label,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    isLoading && rows.isEmpty() -> Text("Chargement de la rediffusion…", color = colors.textSecondary, style = BtvType.body)
                    hasError && rows.isEmpty() -> Unit
                    rows.isEmpty() -> Text(
                        when {
                            searching -> "Aucun programme ne correspond."
                            isContinue -> "Aucune rediffusion en cours."
                            else -> "Aucun programme dans l'archive."
                        },
                        color = colors.textSecondary,
                        style = BtvType.body
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(BtvDimens.listSpacing)
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                            var rowFocused by remember(row.key) { mutableStateOf(false) }
                            val rowModifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(rowRequesters[index])
                                .onFocusChanged {
                                    rowFocused = it.isFocused
                                    if (it.isFocused) {
                                        focusedIndex = index
                                        tabFocused = false
                                        onAirFocused = row is ReplayRow.OnAir
                                        if (row is ReplayRow.Program) onPreview(row.item.id)
                                    }
                                }
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            when {
                                                index > 0 -> focusRow(index - 1)
                                                days.isNotEmpty() -> focusTab()
                                                else -> searchFocusRequester.requestFocus()
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            if (index == rows.lastIndex) onFocusMiniPlayer?.invoke() else focusRow(index + 1)
                                            true
                                        }
                                        // Tabs run from today (left) to older days (right).
                                        Key.DirectionRight -> {
                                            val dayIndex = days.indexOf(day)
                                            if (dayIndex in 0 until days.lastIndex) {
                                                changeDayFromList(dayIndex + 1, rowStart(row))
                                            }
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            val dayIndex = days.indexOf(day)
                                            // On the most recent day, Left goes back to the channels.
                                            if (dayIndex > 0) {
                                                changeDayFromList(dayIndex - 1, rowStart(row))
                                                true
                                            } else false
                                        }
                                        Key.DirectionCenter, Key.Enter -> {
                                            if (row is ReplayRow.OnAir) onStartOver() else onOpen(row.key)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .focusable()
                                .clickable { if (row is ReplayRow.OnAir) onStartOver() else onOpen(row.key) }
                            when (row) {
                                is ReplayRow.Program -> ProgramRow(
                                    item = row.item,
                                    showDate = isContinue || searching,
                                    progress = progress[row.item.id],
                                    isWatched = row.item.id in watchedIds,
                                    isFocused = rowFocused,
                                    modifier = rowModifier
                                )
                                is ReplayRow.OnAir -> OnAirRow(row.item, now, rowFocused, rowModifier)
                            }
                        }
                    }
                }
            }
            ReplayDetailPanel(
                item = if (onAirFocused) onAir else selected,
                isOnAir = onAirFocused && onAir != null,
                hasGuide = hasGuide,
                progress = selected?.id?.let(progress::get),
                isWatched = selected?.id in watchedIds,
                now = now,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ProgramRow(item: ContentItem, showDate: Boolean, progress: Float?, isWatched: Boolean, isFocused: Boolean, modifier: Modifier) {
    val colors = BtvTheme.colors
    val start = item.epgStartTime
    Column(
        modifier
            .btvFocusSurface(isFocused, shape = BtvShapes.card, restColor = colors.surface, focusedColor = colors.surface2)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                start?.let { formatTime(it) }.orEmpty(),
                Modifier.width(54.dp),
                color = if (isFocused) colors.accentOnSurface else colors.textMuted,
                style = BtvType.label
            )
            Column(Modifier.weight(1f)) {
                Text(item.name, color = colors.textPrimary, style = BtvType.body,
                    fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(
                    item.badge,
                    if (showDate) start?.let { formatDay(it) } else null,
                    item.duration
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, color = colors.textMuted, style = BtvType.meta, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (isWatched) Text("✓ Vu", color = colors.accentOnSurface, style = BtvType.meta, fontWeight = FontWeight.SemiBold)
        }
        if (!isWatched && progress != null && progress > 0f) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = if (isFocused) colors.accentOnSurface else colors.textPrimary.copy(alpha = 0.35f),
                trackColor = colors.surface3
            )
        }
    }
}

@Composable
private fun OnAirRow(item: ContentItem, now: Long, isFocused: Boolean, modifier: Modifier) {
    val colors = BtvTheme.colors
    val start = item.epgStartTime ?: now
    val end = item.epgEndTime ?: now
    val elapsed = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .btvSelectionBar(!isFocused)
            .btvFocusSurface(isFocused, shape = BtvShapes.card, restColor = colors.surface2, focusedColor = colors.surface3)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▶ EN COURS", Modifier.width(92.dp), color = colors.accentOnSurface, style = BtvType.overline)
            Column(Modifier.weight(1f)) {
                Text(item.name, color = colors.textPrimary, style = BtvType.body, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatTime(start)} – ${formatTime(end)} · OK : reprendre depuis le début",
                    color = colors.textSecondary, style = BtvType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { elapsed },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.accentOnSurface, trackColor = colors.surface3
        )
    }
}

@Composable
private fun ReplayDetailPanel(
    item: ContentItem?,
    isOnAir: Boolean,
    hasGuide: Boolean,
    progress: Float?,
    isWatched: Boolean,
    now: Long,
    modifier: Modifier
) {
    val colors = BtvTheme.colors
    Column(modifier.background(colors.surface, BtvShapes.panel).padding(22.dp)) {
        if (item == null) {
            Text("Sélectionner un programme", color = colors.textSecondary, style = BtvType.body)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(colors.surface3, BtvShapes.control).padding(4.dp), contentAlignment = Alignment.Center) {
                if (item.posterUrl.isNullOrBlank()) Text("TV", color = colors.textMuted, style = BtvType.overline)
                else AsyncImage(model = item.posterUrl, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Text(item.name, Modifier.weight(1f), color = colors.textPrimary, style = BtvType.section,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        val start = item.epgStartTime
        val end = item.epgEndTime
        if (start != null && end != null) {
            Text(
                listOfNotNull(item.badge, formatDay(start).replaceFirstChar { it.uppercase(Locale.FRANCE) },
                    "${formatTime(start)} – ${formatTime(end)}", item.duration).joinToString(" · "),
                color = colors.textSecondary, style = BtvType.meta.copy(fontSize = 13.sp)
            )
            Spacer(Modifier.height(10.dp))
        }
        when {
            isOnAir -> {
                Text("EN COURS · OK pour le reprendre depuis le début", color = colors.accentOnSurface, style = BtvType.label)
                if (start != null) Text("Diffusé depuis ${((now - start) / 60_000L).coerceAtLeast(0)} min",
                    color = colors.textMuted, style = BtvType.meta)
            }
            isWatched -> Text("✓ Vu", color = colors.accentOnSurface, style = BtvType.label)
            progress != null && progress > 0f -> {
                Text("Reprise à ${(progress * 100).toInt()} %", color = colors.accentOnSurface, style = BtvType.label)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = colors.accentOnSurface, trackColor = colors.surface3
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            !hasGuide -> Text(
                "Pas de guide TV pour cette chaîne : l'archive est proposée heure par heure.",
                color = colors.textMuted, style = BtvType.body
            )
            !item.plot.isNullOrBlank() -> Text(item.plot, color = colors.textPrimary.copy(alpha = 0.85f), style = BtvType.body,
                maxLines = 14, overflow = TextOverflow.Ellipsis)
            else -> Text("Pas de description.", color = colors.textMuted, style = BtvType.body)
        }
    }
}

private fun formatTime(time: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))
private fun formatDay(time: Long): String = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(time))

private fun rowStart(row: ReplayRow): Long? = when (row) {
    is ReplayRow.Program -> row.item.epgStartTime
    is ReplayRow.OnAir -> row.item.epgStartTime
}

private fun minuteOfDay(time: Long): Int {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = time }
    return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
}
