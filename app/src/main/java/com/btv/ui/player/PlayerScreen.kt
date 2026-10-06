package com.btv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import android.view.ViewGroup
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import com.btv.ui.theme.BtvGreen
import com.btv.R

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onExit: () -> Unit,
    onMiniPlayer: () -> Unit,
    onLoadInitial: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val resumePrompt by viewModel.resumePrompt.collectAsState()
    val nextSeasonPrompt by viewModel.nextSeasonPrompt.collectAsState()
    val miniPlayerRequested by viewModel.miniPlayerRequested.collectAsState()
    val exitRequested by viewModel.exitRequested.collectAsState()

    LaunchedEffect(Unit) { onLoadInitial?.invoke() }

    LaunchedEffect(miniPlayerRequested) {
        if (miniPlayerRequested) {
            viewModel.consumeMiniPlayerRequest()
            onMiniPlayer()
        }
    }
    LaunchedEffect(exitRequested) {
        if (exitRequested) {
            viewModel.consumeExitRequest()
            viewModel.stopAndExit()
            onExit()
        }
    }

    val focusRequester = remember { FocusRequester() }
    // Re-requested whenever the resume dialog isn't showing - including right
    // after it closes (Reprendre/Recommencer), since ResumeDialog steals focus
    // onto its own FocusRequester while shown and nothing else claims it back
    // afterwards. Without this, the very next key press (often Back) finds
    // nothing focused in this tree and falls through to Android's default
    // back dispatch instead of onBackPressed()'s OSD/exit-dialog state
    // machine, popping straight out of the player.
    LaunchedEffect(resumePrompt == null && nextSeasonPrompt == null) {
        if (resumePrompt != null || nextSeasonPrompt != null) return@LaunchedEffect
        var attempts = 0
        while (attempts < 20) {
            try {
                focusRequester.requestFocus()
                break
            } catch (e: IllegalStateException) {
                attempts++
                delay(50)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (resumePrompt != null || nextSeasonPrompt != null) return@onKeyEvent false // dialog owns focus/keys while shown
                when (keyEvent.key) {
                    Key.DirectionUp -> { viewModel.onDirectionUp(); true }
                    Key.DirectionDown -> { viewModel.onDirectionDown(); true }
                    Key.DirectionLeft -> { viewModel.onDirectionLeft(); true }
                    Key.DirectionRight -> { viewModel.onDirectionRight(); true }
                    Key.DirectionCenter, Key.Enter -> { viewModel.onCenter(); true }
                    Key.Back -> { viewModel.onBackPressed(); true }
                    else -> false
                }
            }
    ) {
        if (viewModel.player != null) {
            val subtitleStyle by rememberSubtitleStylePrefs()
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = viewModel.player
                        useController = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = {
                    it.player = viewModel.player
                    it.subtitleView?.applyStylePrefs(subtitleStyle)
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (uiState.errorMessage != null) {
            ErrorOverlay(
                message = uiState.errorMessage!!,
                retryCount = uiState.retryCount,
                maxRetries = uiState.maxRetries,
                onRetry = viewModel::retryCurrentStream,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (uiState.isLoading || uiState.isPrebuffering || uiState.isReconnecting) {
            // Port of Tizen's showPlayerLoadingSpinner (js/player.js:186-193):
            // this state existed already (STATE_BUFFERING) but nothing ever
            // rendered it, so buffering looked like a frozen screen instead
            // of a visible spinner.
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when {
                        uiState.isLive && uiState.isReconnecting ->
                            uiState.liveFallbackName?.let { "Reconnexion au direct... ($it)" } ?: "Reconnexion au direct..."
                        uiState.isPrebuffering -> "Mise en réserve du direct..."
                        uiState.retryCount > 0 -> "Reconnexion (${uiState.retryCount}/${uiState.maxRetries})..."
                        else -> "Chargement..."
                    },
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

        uiState.flashMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(message, color = Color.White, fontSize = 14.sp)
            }
        }

        if (uiState.osdVisible && resumePrompt == null && nextSeasonPrompt == null) {
            PlayerOsd(uiState = uiState)
        }

        if (uiState.trackMenuType != null) {
            TrackMenuOverlay(uiState = uiState)
        }

        if (uiState.showExitDialog) {
            ExitPlayerDialog(focusIndex = uiState.exitDialogFocusIndex)
        }

        resumePrompt?.let { prompt ->
            ResumeDialog(
                title = uiState.contentName,
                resumePositionMs = prompt.resumePositionMs,
                onResume = { viewModel.confirmResume(true) },
                onRestart = { viewModel.confirmResume(false) },
                onCancel = { viewModel.cancelResume() }
            )
        }

        nextSeasonPrompt?.let { prompt ->
            NextSeasonDialog(
                title = (uiState.seriesName?.let { "$it — " } ?: "") + "Saison ${prompt.seasonNum}",
                onYes = { viewModel.confirmNextSeason(true) },
                onNo = { viewModel.confirmNextSeason(false) }
            )
        }
    }
}

@Composable
private fun PlayerOsd(uiState: PlayerUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        // One floating inset card (js/style.css .player-osd: inset margins,
        // rounded corners, translucent dark background) - the episode list
        // expands INSIDE it, below the buttons row, instead of a separate
        // opaque block stacked above.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .shadow(14.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .background(Color(0xFF0A0A0A).copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Title row + clock, mirrors the Tizen OSD header (no fake "HD"
            // badge - it was a static label, not a real quality readout).
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                Column {
                    Text(uiState.contentName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(currentClockText(), color = Color(0xFFCCCCCC), fontSize = 10.sp)
                    if (uiState.duration > 0 && !uiState.isLive) {
                        Spacer(Modifier.width(6.dp))
                        Text("-${formatTime(uiState.duration - uiState.currentPosition)}", color = Color(0xFFCCCCCC), fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (uiState.isLive) {
                Text("● Direct", color = BtvGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (uiState.duration > 0 && (!uiState.isLive || uiState.isSeekable)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(uiState.currentPosition), color = Color.White, fontSize = 9.sp)
                    Spacer(Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(if (uiState.osdZone == OsdZone.SEEK) 4.dp else 3.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((uiState.currentPosition.toFloat() / uiState.duration).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(BtvGreen, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(formatTime(uiState.duration), color = Color.White, fontSize = 9.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val buttons = uiState.playerButtons
                buttons.forEachIndexed { index, button ->
                    OsdButton(
                        button = button,
                        uiState = uiState,
                        isFocused = uiState.osdZone == OsdZone.BUTTONS && uiState.focusedButtonIndex == index
                    )
                    if (index != buttons.lastIndex) Spacer(Modifier.width(5.dp))
                }
            }

            if (uiState.osdZone == OsdZone.EPISODES) {
                Spacer(Modifier.height(6.dp))
                EpisodeDrawer(uiState = uiState)
            }
        }
    }
}

private fun playerButtonLabel(button: PlayerButton, uiState: PlayerUiState): String = when (button) {
    PlayerButton.REWIND -> "Reculer"
    PlayerButton.PLAYPAUSE -> if (uiState.isPlaying) "Pause" else "Lire"
    PlayerButton.FORWARD -> "Avancer"
    PlayerButton.NEXT -> "Suivant"
    PlayerButton.AUDIO -> uiState.currentAudioLabel.ifEmpty { "Audio" }
    PlayerButton.SUBTITLE -> uiState.currentSubtitleLabel
    PlayerButton.QUALITY -> uiState.currentQualityLabel
    PlayerButton.PIP -> "Réduire"
}

@Composable
private fun OsdButton(button: PlayerButton, uiState: PlayerUiState, isFocused: Boolean) {
    val icon = when (button) {
        PlayerButton.REWIND -> R.drawable.ic_player_rewind
        PlayerButton.PLAYPAUSE -> if (uiState.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
        PlayerButton.FORWARD -> R.drawable.ic_player_forward
        PlayerButton.NEXT -> R.drawable.ic_player_next
        PlayerButton.AUDIO -> R.drawable.ic_player_audio
        PlayerButton.SUBTITLE -> R.drawable.ic_player_subtitle
        PlayerButton.QUALITY -> R.drawable.ic_player_quality
        PlayerButton.PIP -> R.drawable.ic_player_minimize
    }
    Row(
        modifier = Modifier
            .background(if (isFocused) BtvGreen.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
            .border(2.dp, if (isFocused) BtvGreen else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), contentDescription = playerButtonLabel(button, uiState), tint = Color.White,
            modifier = Modifier.width(16.dp).height(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(playerButtonLabel(button, uiState), color = Color.White, fontSize = 10.sp,
            modifier = Modifier.widthIn(max = 84.dp),
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Port of Tizen's osd-episode-list (js/player.js openEpisodeList): other items in the same category/playlist. */
@Composable
private fun EpisodeDrawer(uiState: PlayerUiState) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(uiState.episodeFocusIndex) {
        try { listState.animateScrollToItem(maxOf(0, uiState.episodeFocusIndex - 2)) } catch (e: IllegalStateException) {}
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(uiState.zapList) { index, item ->
                val isFocused = index == uiState.episodeFocusIndex
                val isPlaying = index == uiState.zapIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp)
                        .background(if (isFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                        .border(1.dp, if (isFocused) Color(0xFF17B355) else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.name,
                        modifier = Modifier.width(20.dp).height(20.dp).background(Color(0xFF2a2a2a), RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.name + if (isPlaying) "  ●" else "",
                        color = if (isPlaying) Color(0xFF4CDA3E) else Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackMenuOverlay(uiState: PlayerUiState) {
    val options = uiState.trackMenuOptions()
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .padding(end = 40.dp)
                .background(Color(0xFF1a1a1a), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                when (uiState.trackMenuType) {
                    TrackMenuType.AUDIO -> "Piste audio"
                    TrackMenuType.QUALITY -> "Qualité"
                    else -> "Sous-titres"
                },
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            if (options.isEmpty()) {
                Text("Aucune piste disponible.", color = Color(0xFF999999), fontSize = 12.sp)
            }
            options.forEachIndexed { index, option ->
                val isFocused = index == uiState.trackMenuFocusIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .background(if (isFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .border(2.dp, if (isFocused) BtvGreen else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        (if (option.isSelected) "✓ " else "") + option.label,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ExitPlayerDialog(focusIndex: Int) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .shadow(15.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .background(Color(0xFF1a1a1a), RoundedCornerShape(10.dp))
                .width(280.dp)
                .padding(16.dp)
        ) {
            Text("Quitter la lecture ?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column {
                ResumeDialogButton("Réduire (mini-lecteur)", isFocused = focusIndex == 0, onClick = {}, fillWidth = true)
                Spacer(Modifier.height(8.dp))
                ResumeDialogButton("Sortir", isFocused = focusIndex == 1, onClick = {}, fillWidth = true)
            }
        }
    }
}

private fun currentClockText(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(System.currentTimeMillis())

@Composable
private fun ResumeDialog(
    title: String,
    resumePositionMs: Long,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit
) {
    var focusIndex by remember { mutableIntStateOf(0) } // 0 = Reprendre, 1 = Recommencer
    var remainingSeconds by remember { mutableIntStateOf(30) }
    val resumeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        var attempts = 0
        while (attempts < 20) {
            try {
                resumeFocusRequester.requestFocus()
                break
            } catch (e: IllegalStateException) {
                attempts++
                delay(50)
            }
        }
    }

    // Same 30s auto-timeout as Tizen's RESUME_DIALOG_TIMEOUT_S, defaulting to
    // "Recommencer" (the cautious option, js/modals.js) rather than resuming
    // silently.
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
        onRestart()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .shadow(15.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .background(Color(0xFF1a1a1a), RoundedCornerShape(10.dp))
                .width(300.dp)
                .focusRequester(resumeFocusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (keyEvent.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            focusIndex = if (focusIndex == 0) 1 else 0
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (focusIndex == 0) onResume() else onRestart()
                            true
                        }
                        Key.Back -> { onCancel(); true }
                        else -> false
                    }
                }
                .padding(16.dp)
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(
                "Reprendre à ${formatTime(resumePositionMs)} ou recommencer depuis le début ?",
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            Row {
                ResumeDialogButton("Reprendre", isFocused = focusIndex == 0, onClick = onResume, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ResumeDialogButton("Recommencer", isFocused = focusIndex == 1, onClick = onRestart, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Reprend depuis le début dans ${remainingSeconds}s sans réponse...",
                color = Color(0xFF999999),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * "Saison suivante ?" - offered at the end of the last episode of a season
 * when the series has a following one (js/modals.js openNextSeasonDialog).
 * Unlike ResumeDialog, the 15s countdown defaults to "Oui" (continuing
 * playback is the expected action here, not a risky one) and Back behaves
 * like "Non" instead of cancelling outright.
 */
@Composable
private fun NextSeasonDialog(
    title: String,
    onYes: () -> Unit,
    onNo: () -> Unit
) {
    var focusIndex by remember { mutableIntStateOf(0) } // 0 = Oui, 1 = Non
    var remainingSeconds by remember { mutableIntStateOf(15) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        var attempts = 0
        while (attempts < 20) {
            try {
                focusRequester.requestFocus()
                break
            } catch (e: IllegalStateException) {
                attempts++
                delay(50)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
        onYes()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .shadow(15.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .background(Color(0xFF1a1a1a), RoundedCornerShape(10.dp))
                .width(300.dp)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (keyEvent.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            focusIndex = if (focusIndex == 0) 1 else 0
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (focusIndex == 0) onYes() else onNo()
                            true
                        }
                        Key.Back -> { onNo(); true }
                        else -> false
                    }
                }
                .padding(16.dp)
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text("Lire la saison suivante ?", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Row {
                ResumeDialogButton("Oui", isFocused = focusIndex == 0, onClick = onYes, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ResumeDialogButton("Non", isFocused = focusIndex == 1, onClick = onNo, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Saison suivante dans ${remainingSeconds}s...",
                color = Color(0xFF999999),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ResumeDialogButton(
    label: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false
) {
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .background(if (isFocused) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .border(2.dp, if (isFocused) BtvGreen else Color.Transparent, RoundedCornerShape(6.dp))
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    retryCount: Int,
    maxRetries: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Erreur de lecture",
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = message,
            color = Color.LightGray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        // maxRetries == 0: live, bounded by outage time rather than a count - always retryable by hand.
        if (maxRetries > 0) {
            Text(
                text = "Tentative $retryCount/$maxRetries",
                color = Color.Yellow,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        if (maxRetries == 0 || retryCount < maxRetries) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Réessayer")
            }
        } else {
            Text(
                text = "Nombre maximum de tentatives atteint",
                color = Color.Red,
                fontSize = 12.sp
            )
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    if (milliseconds < 0) return "--:--"
    val seconds = (milliseconds / 1000) % 60
    val minutes = (milliseconds / (1000 * 60)) % 60
    val hours = (milliseconds / (1000 * 60 * 60))
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
