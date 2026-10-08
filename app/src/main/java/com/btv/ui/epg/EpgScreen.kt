package com.btv.ui.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.ui.browse.EpgProgram
import androidx.compose.foundation.layout.size
import com.btv.ui.components.BtvSectionTitle
import com.btv.ui.components.btvFocusSurface
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvType
import com.btv.ui.theme.BtvTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EpgScreen(
    channelName: String,
    programs: List<EpgProgram>,
    isLoading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    onBack: () -> Unit
) {
    var selectedIndex by remember(programs) {
        val currentIndex = programs.indexOfFirst { it.isCurrentlyAiring() }
        mutableIntStateOf(if (currentIndex >= 0) currentIndex else 0)
    }
    val focusRequesters = remember(programs.size) { List(programs.size) { FocusRequester() } }
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val retryFocusRequester = remember { FocusRequester() }
    var retryFocused by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(onBack = onBack)

    LaunchedEffect(programs, error) {
        if (error != null) {
            retryFocusRequester.requestFocus()
            return@LaunchedEffect
        }
        if (programs.isEmpty()) return@LaunchedEffect
        lazyListState.scrollToItem(maxOf(0, selectedIndex - 1))
        var attempts = 0
        while (attempts < 20 && focusRequesters.isNotEmpty()) {
            try {
                focusRequesters[selectedIndex].requestFocus()
                break
            } catch (e: IllegalStateException) {
                attempts++
                kotlinx.coroutines.delay(50)
            }

        }
        try {
            lazyListState.animateScrollToItem(maxOf(0, selectedIndex - 1))
        } catch (e: IllegalStateException) {
        }
    }

    val selectedProgram = programs.getOrNull(selectedIndex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BtvTheme.colors.bgBlack)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Back) {
                    onBack()
                    true
                } else {
                    false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = BtvDimens.screenPaddingH, end = BtvDimens.screenPaddingH, top = BtvDimens.screenPaddingV, bottom = 18.dp)
                    .pointerInput(Unit) { detectTapGestures { onBack() } },
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(com.btv.R.drawable.ic_lucide_arrow_left),
                    contentDescription = null,
                    tint = BtvTheme.colors.textSecondary,
                    modifier = Modifier.padding(end = 12.dp).size(18.dp)
                )
                BtvSectionTitle(title = "Guide TV", subtitle = channelName)
            }

            if (isLoading && programs.isEmpty()) {
                Text("Chargement du guide…", Modifier.padding(horizontal = BtvDimens.screenPaddingH), color = BtvTheme.colors.textSecondary, style = BtvType.body)
            }
            if (error != null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = BtvDimens.screenPaddingH)
                        .focusRequester(retryFocusRequester)
                        .onFocusChanged { retryFocused = it.isFocused }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> { onRetry(); true }
                                Key.DirectionDown -> {
                                    if (focusRequesters.isNotEmpty()) focusRequesters[selectedIndex].requestFocus()
                                    true
                                }
                                else -> false
                            }
                        }
                        .semantics { contentDescription = "Réessayer le guide TV" }
                        .focusable()
                        .btvFocusSurface(retryFocused, shape = BtvShapes.card, restColor = BtvTheme.colors.surface, focusedColor = BtvTheme.colors.surface2)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(error, color = BtvTheme.colors.textPrimary, style = BtvType.body)
                    Text("Réessayer · OK", color = BtvTheme.colors.accentOnSurface, style = BtvType.label)
                }
            } else if (!isLoading && programs.isEmpty()) {
                Text("Programme non disponible.", Modifier.padding(horizontal = BtvDimens.screenPaddingH), color = BtvTheme.colors.textSecondary, style = BtvType.body)
            }

            // Detail panel for the selected program
            if (selectedProgram != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = BtvDimens.screenPaddingH)) {
                    Text(
                        text = selectedProgram.title,
                        color = BtvTheme.colors.textPrimary,
                        style = BtvType.hero.copy(fontSize = 26.sp, lineHeight = 32.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = listOf(
                            "${formatTime(selectedProgram.startTime)} - ${formatTime(selectedProgram.endTime)}",
                            selectedProgram.genre
                        ).filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = BtvTheme.colors.accentOnSurface,
                        style = BtvType.meta.copy(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = selectedProgram.description,
                        color = BtvTheme.colors.textSecondary,
                        style = BtvType.body,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    )

                    if (selectedProgram.isCurrentlyAiring()) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { selectedProgram.progressFraction() },
                            modifier = Modifier.fillMaxWidth(),
                            color = BtvTheme.colors.accentOnSurface,
                            trackColor = BtvTheme.colors.surface3
                        )
                    }
                }
            }

            Spacer(Modifier.height(BtvDimens.sectionSpacing))

            // Timeline
            LazyRow(
                state = lazyListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BtvDimens.screenPaddingH, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(programs) { index, program ->
                    EpgItem(
                        program = program,
                        isSelected = index == selectedIndex,
                        modifier = Modifier
                            .focusRequester(focusRequesters[index])
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (keyEvent.key) {
                                    Key.DirectionRight -> {
                                        if (selectedIndex < programs.size - 1) {
                                            selectedIndex++
                                            try { focusRequesters[selectedIndex].requestFocus() } catch (e: IllegalStateException) {}
                                            coroutineScope.launch {
                                                try {
                                                    lazyListState.animateScrollToItem(maxOf(0, selectedIndex - 1))
                                                } catch (e: IllegalStateException) {}
                                            }
                                        }
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        if (selectedIndex > 0) {
                                            selectedIndex--
                                            try { focusRequesters[selectedIndex].requestFocus() } catch (e: IllegalStateException) {}
                                            coroutineScope.launch {
                                                try {
                                                    lazyListState.animateScrollToItem(maxOf(0, selectedIndex - 1))
                                                } catch (e: IllegalStateException) {}
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    Key.DirectionCenter, Key.Enter -> {
                                        onBack()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusable()
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    selectedIndex = index
                                }
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgItem(
    program: EpgProgram,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val isLive = program.isCurrentlyAiring()
    val colors = BtvTheme.colors

    Column(
        modifier = modifier
            .width(172.dp)
            .btvFocusSurface(
                isSelected,
                shape = BtvShapes.card,
                restColor = if (isLive) colors.surface2 else colors.surface,
                focusedColor = colors.surface3,
                focusScale = BtvMotion.FOCUS_SCALE_SMALL
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLive) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(6.dp)
                        .background(com.btv.ui.theme.BtvDanger, RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = "${formatTime(program.startTime)} - ${formatTime(program.endTime)}",
                color = if (isLive) colors.accentOnSurface else colors.textMuted,
                style = BtvType.meta
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = program.title,
            color = colors.textPrimary,
            style = BtvType.title.copy(fontSize = 13.sp, lineHeight = 17.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = program.genre,
            color = colors.textMuted,
            style = BtvType.meta.copy(fontSize = 11.sp),
            maxLines = 1
        )
    }
}

private fun formatTime(millis: Long): String {
    return SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(millis))
}
