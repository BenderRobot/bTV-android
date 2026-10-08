package com.btv.ui.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
import com.btv.ui.browse.EpgProgram
import com.btv.ui.components.BtvOverline
import com.btv.ui.components.BtvSectionTitle
import com.btv.ui.components.btvFocusSurface
import com.btv.ui.components.btvSelectionBar
import com.btv.ui.components.requestFocusWithRetry
import com.btv.ui.components.onTap
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvLogoTile
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** A line of the guide list: a day heading or a programme. */
private sealed interface GuideEntry {
    val key: String
    data class Day(val label: String, override val key: String) : GuideEntry
    data class Program(val index: Int, override val key: String) : GuideEntry
}

/**
 * Full guide of one channel, the coming programmes as a vertical list
 * grouped by day (left) and the focused one's details (right), laid out like
 * Rediffusion. Up/Down walk the programmes, Left/Right jump a day, OK
 * watches the channel, Back closes.
 */
@Composable
fun EpgScreen(
    channelName: String,
    programs: List<EpgProgram>,
    isLoading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    channelLogo: String? = null,
    onWatch: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    val colors = BtvTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // From what is airing now onwards.
    val upcoming = remember(programs) {
        val t = System.currentTimeMillis()
        programs.filter { it.endTime > t }.sortedBy { it.startTime }
    }
    val days = remember(upcoming) { upcoming.map { dayOf(it.startTime) } }
    val entries = remember(upcoming) {
        buildList {
            upcoming.forEachIndexed { index, program ->
                if (index == 0 || days[index] != days[index - 1]) {
                    add(GuideEntry.Day(dayLabel(days[index]), "day_${days[index]}"))
                }
                add(GuideEntry.Program(index, program.id))
            }
        }
    }
    val entryIndexOf = remember(entries) {
        IntArray(upcoming.size).also { map ->
            entries.forEachIndexed { entryIndex, entry -> if (entry is GuideEntry.Program) map[entry.index] = entryIndex }
        }
    }
    var selectedIndex by remember(upcoming) { mutableIntStateOf(0) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val requesters = remember(upcoming.size) { List(upcoming.size) { FocusRequester() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val retryFocusRequester = remember { FocusRequester() }
    var retryFocused by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(onBack = onBack)

    fun focusProgram(index: Int) {
        if (index !in upcoming.indices) return
        selectedIndex = index
        scope.launch {
            runCatching { listState.animateScrollToItem((entryIndexOf[index] - 2).coerceAtLeast(0)) }
            requesters[index].requestFocusWithRetry(attempts = 10, delayMs = 30)
        }
    }

    /** First programme of the next (+1) or previous (-1) day. */
    fun jumpDay(from: Int, direction: Int) {
        val day = days.getOrNull(from) ?: return
        val target = if (direction > 0) {
            days.indexOfFirst { it > day }
        } else {
            val previousDay = days.lastOrNull { it < day } ?: return
            days.indexOfFirst { it == previousDay }
        }
        if (target >= 0) focusProgram(target)
    }

    LaunchedEffect(upcoming, error) {
        if (error != null) {
            retryFocusRequester.requestFocusWithRetry()
            return@LaunchedEffect
        }
        if (upcoming.isNotEmpty()) focusProgram(selectedIndex.coerceIn(upcoming.indices))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBlack)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Back) {
                    onBack()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = BtvDimens.screenPaddingH, vertical = BtvDimens.screenPaddingV)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { onBack() } },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_arrow_left),
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(14.dp))
            if (!channelLogo.isNullOrBlank()) {
                Box(
                    Modifier.size(44.dp).background(BtvLogoTile, BtvShapes.control).padding(5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(model = channelLogo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
            }
            BtvSectionTitle(title = "Guide TV", subtitle = com.btv.util.displayTitle(channelName))
        }
        Spacer(Modifier.height(20.dp))

        if (error != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(retryFocusRequester)
                    .onFocusChanged { retryFocused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter -> { onRetry(); true }
                            Key.DirectionDown -> {
                                if (upcoming.isNotEmpty()) focusProgram(selectedIndex)
                                true
                            }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = "Réessayer le guide TV" }
                    .focusable()
                    .btvFocusSurface(retryFocused, shape = BtvShapes.card, restColor = colors.surface, focusedColor = colors.surface2)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(error, color = colors.textPrimary, style = BtvType.body)
                Text("Réessayer · OK", color = colors.accentOnSurface, style = BtvType.label)
            }
            Spacer(Modifier.height(14.dp))
        }

        when {
            upcoming.isEmpty() && isLoading ->
                Text("Chargement du guide…", color = colors.textSecondary, style = BtvType.body)
            upcoming.isEmpty() && error == null ->
                Text("Programme non disponible.", color = colors.textSecondary, style = BtvType.body)
            upcoming.isEmpty() -> Unit
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1.1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(BtvDimens.listSpacing)
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.key }) { _, entry ->
                        when (entry) {
                            is GuideEntry.Day -> BtvOverline(
                                entry.label,
                                Modifier.padding(start = 4.dp, top = if (entry == entries.first()) 0.dp else 14.dp, bottom = 4.dp)
                            )
                            is GuideEntry.Program -> {
                                val index = entry.index
                                GuideRow(
                                    program = upcoming[index],
                                    now = now,
                                    isFocused = focusedIndex == index,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(requesters[index])
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                focusedIndex = index
                                                selectedIndex = index
                                            } else if (focusedIndex == index) focusedIndex = -1
                                        }
                                        .onKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                            when (event.key) {
                                                Key.DirectionUp -> {
                                                    if (index > 0) focusProgram(index - 1)
                                                    else if (error != null) runCatching { retryFocusRequester.requestFocus() }
                                                    true
                                                }
                                                Key.DirectionDown -> { focusProgram(index + 1); true }
                                                Key.DirectionRight -> { jumpDay(index, 1); true }
                                                Key.DirectionLeft -> { jumpDay(index, -1); true }
                                                Key.DirectionCenter, Key.Enter -> {
                                                    if (onWatch != null) onWatch() else onBack()
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                        .focusable()
                                        // Touch: the first tap shows the programme, a second one watches the channel.
                                        .onTap {
                                            if (selectedIndex == index) onWatch?.invoke() else selectedIndex = index
                                        }
                                )
                            }
                        }
                    }
                }
                GuideDetail(
                    program = upcoming.getOrNull(selectedIndex),
                    now = now,
                    canWatch = onWatch != null,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun GuideRow(program: EpgProgram, now: Long, isFocused: Boolean, modifier: Modifier) {
    val colors = BtvTheme.colors
    val isNow = program.isCurrentlyAiring(now)
    Row(
        modifier = modifier
            .btvSelectionBar(isNow && !isFocused)
            .btvFocusSurface(
                isFocused,
                shape = BtvShapes.card,
                restColor = if (isNow) colors.surface2 else colors.surface,
                focusedColor = colors.surface3
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatTime(program.startTime),
            Modifier.width(62.dp),
            color = if (isFocused || isNow) colors.accentOnSurface else colors.textMuted,
            style = BtvType.label
        )
        Column(Modifier.weight(1f)) {
            Text(
                program.title.ifBlank { "Programme" },
                color = colors.textPrimary,
                style = BtvType.body,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isNow) {
                Text("EN COURS", color = colors.accentOnSurface, style = BtvType.overline)
            } else {
                Text(formatMinutes(program.endTime - program.startTime), color = colors.textMuted, style = BtvType.meta)
            }
        }
    }
}

@Composable
private fun GuideDetail(program: EpgProgram?, now: Long, canWatch: Boolean, modifier: Modifier) {
    val colors = BtvTheme.colors
    Column(modifier.background(colors.surface, BtvShapes.panel).padding(28.dp)) {
        if (program == null) return@Column
        val isNow = program.isCurrentlyAiring(now)
        BtvOverline(dayLabel(dayOf(program.startTime)))
        Spacer(Modifier.height(8.dp))
        Text(
            program.title.ifBlank { "Programme" },
            color = colors.textPrimary,
            style = BtvType.hero.copy(fontSize = 26.sp, lineHeight = 32.sp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${formatTime(program.startTime)} – ${formatTime(program.endTime)}  ·  ${formatMinutes(program.endTime - program.startTime)}",
            color = colors.textSecondary,
            style = BtvType.meta.copy(fontSize = 14.sp)
        )
        if (isNow) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.surface3, BtvShapes.small)) {
                Box(
                    Modifier
                        .fillMaxWidth(program.progressFraction(now))
                        .height(3.dp)
                        .background(colors.accentOnSurface, BtvShapes.small)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "EN COURS · encore ${formatMinutes(program.endTime - now)}",
                color = colors.accentOnSurface,
                style = BtvType.label
            )
        }
        Spacer(Modifier.height(18.dp))
        if (program.description.isNotBlank()) {
            Text(
                program.description,
                color = colors.textPrimary.copy(alpha = 0.85f),
                style = BtvType.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
                maxLines = 10,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text("Pas de résumé pour ce programme.", color = colors.textMuted, style = BtvType.body)
        }
        if (canWatch) {
            Spacer(Modifier.weight(1f))
            Text("OK : regarder la chaîne", color = colors.accentOnSurface, style = BtvType.label)
        }
    }
}

private fun dayOf(timeMs: Long): LocalDate = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).toLocalDate()

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Aujourd'hui"
        today.plusDays(1) -> "Demain"
        else -> DAY_FORMAT.format(day).replaceFirstChar { it.uppercase(Locale.FRANCE) }
    }
}

private fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(millis))

private fun formatMinutes(durationMs: Long): String {
    val minutes = (durationMs / 60_000L).toInt().coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$minutes min"
        rest == 0 -> "$hours h"
        else -> "$hours h ${rest.toString().padStart(2, '0')}"
    }
}
