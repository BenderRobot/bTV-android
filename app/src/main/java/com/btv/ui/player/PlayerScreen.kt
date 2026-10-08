package com.btv.ui.player

import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import com.btv.ui.components.consumeTaps
import com.btv.ui.components.onTap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.btv.ui.theme.BtvGreenBright
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

    val touch = remember(viewModel) {
        PlayerTouch(
            onButton = viewModel::onButtonTapped,
            onTrackOption = viewModel::onTrackOptionTapped,
            onEpisode = viewModel::onEpisodeTapped,
            onExitChoice = viewModel::onExitChoiceTapped,
            onClose = viewModel::onCloseTapped,
            onSeekFraction = viewModel::onSeekToFraction
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPlayerTouch provides touch) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Touch: a tap on the picture shows / hides the OSD.
            .onTap { viewModel.onScreenTapped() }
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
                        uiState.isReplay && uiState.isReconnecting -> "Connexion à l'archive..."
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

        if (uiState.osdVisible && !uiState.infoVisible && resumePrompt == null && nextSeasonPrompt == null) {
            PlayerOsd(uiState = uiState)
        }

        if (uiState.trackMenuType != null) {
            TrackMenuOverlay(uiState = uiState)
        }

        if (uiState.infoVisible) {
            InfoPanel(uiState = uiState, modifier = Modifier.align(Alignment.CenterEnd))
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
}

/**
 * "Infos": a translucent card over the top of the picture - the film keeps
 * playing underneath. Any key, or a tap, closes it.
 */
@Composable
private fun InfoPanel(uiState: PlayerUiState, modifier: Modifier = Modifier) {
    val info = uiState.info
    val episodeLine = uiState.seriesName?.let { com.btv.util.displayTitle(uiState.contentName) }
    val title = uiState.seriesName ?: com.btv.util.displayTitle(uiState.contentName)
    val muted = Color.White.copy(alpha = 0.6f)
    val shape = RoundedCornerShape(16.dp)
    // Portrait card (9:16) on the right: the left of the picture stays visible.
    Column(
        modifier = modifier
            .padding(end = 28.dp)
            .fillMaxHeight(0.9f)
            .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
            .clip(shape)
            .background(Color(0xFF0A0A0A).copy(alpha = 0.86f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
    ) {
        info?.posterUrl?.let { poster ->
            Box(Modifier.fillMaxWidth().weight(0.42f)) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
                // The artwork fades into the card.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0.5f to Color.Transparent, 1f to Color(0xFF0A0A0A).copy(alpha = 0.95f)
                            )
                        )
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.58f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            episodeLine?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when {
                uiState.isLive -> {
                    val now = uiState.liveNowPlaying
                    Spacer(Modifier.height(10.dp))
                    if (now != null) {
                        Text("${formatClock(now.startMs)}–${formatClock(now.endMs)}", color = BtvGreenBright, fontSize = 13.sp)
                        Text(now.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        now.nextTitle?.let { next ->
                            Spacer(Modifier.height(8.dp))
                            val at = now.nextStartMs?.let { " à ${formatClock(it)}" }.orEmpty()
                            Text("Ensuite$at", color = muted, fontSize = 12.sp)
                            Text(next, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                        }
                    } else {
                        Text("Pas de programme annoncé pour cette chaîne.", color = muted, fontSize = 13.sp)
                    }
                }
                uiState.isInfoLoading -> {
                    Spacer(Modifier.height(10.dp))
                    Text("Chargement des informations…", color = muted, fontSize = 13.sp)
                }
                info == null -> {
                    Spacer(Modifier.height(10.dp))
                    Text("Aucune information disponible pour ce titre.", color = muted, fontSize = 13.sp)
                }
                else -> {
                    val meta = listOfNotNull(info.rating?.let { "★ $it" }) + info.meta
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    info.plot?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    info.director?.let {
                        Spacer(Modifier.height(10.dp))
                        Text("Réalisation", color = muted, fontSize = 11.sp)
                        Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                    }
                    info.cast?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Avec", color = muted, fontSize = 11.sp)
                        Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
/** Touch entry points of the player (tablet / phone); unused on a TV. */
private class PlayerTouch(
    val onButton: (Int) -> Unit = {},
    val onTrackOption: (Int) -> Unit = {},
    val onEpisode: (Int) -> Unit = {},
    val onExitChoice: (Int) -> Unit = {},
    val onClose: () -> Unit = {},
    val onSeekFraction: (Float) -> Unit = {}
)

private val LocalPlayerTouch = androidx.compose.runtime.staticCompositionLocalOf { PlayerTouch() }

@Composable
private fun PlayerOsd(uiState: PlayerUiState) {
    if (com.btv.ui.theme.LocalIsTv.current) PlayerOsdTv(uiState) else PlayerOsdTouch(uiState)
}

/** Series name large, then the episode; a film or a channel: its title alone. */
@Composable
private fun OsdTitle(uiState: PlayerUiState, large: Boolean) {
    val seriesName = uiState.seriesName
    Text(
        seriesName ?: com.btv.util.displayTitle(uiState.contentName),
        color = Color.White,
        fontSize = if (large) 22.sp else 17.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (seriesName != null) {
        Text(
            com.btv.util.displayTitle(uiState.contentName),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = if (large) 14.sp else 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Position on the bar, then the times under it at both ends. */
@Composable
private fun OsdProgress(uiState: PlayerUiState) {
    if (uiState.duration <= 0 || (uiState.isLive && !uiState.isSeekable)) return
    Column(Modifier.fillMaxWidth()) {
        SeekBar(
            fraction = (uiState.currentPosition.toFloat() / uiState.duration).coerceIn(0f, 1f),
            focused = uiState.osdZone == OsdZone.SEEK,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(uiState.currentPosition), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(formatTime(uiState.duration), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

private fun playerButtonIcon(button: PlayerButton, uiState: PlayerUiState): Int = when (button) {
    PlayerButton.PREVIOUS -> R.drawable.ic_player_previous
    PlayerButton.REWIND -> R.drawable.ic_player_rewind
    PlayerButton.PLAYPAUSE -> if (uiState.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
    PlayerButton.FORWARD -> R.drawable.ic_player_forward
    PlayerButton.NEXT -> R.drawable.ic_player_next
    PlayerButton.AUDIO -> R.drawable.ic_player_audio
    PlayerButton.SUBTITLE -> R.drawable.ic_player_subtitle
    PlayerButton.QUALITY -> R.drawable.ic_player_quality
    PlayerButton.LIST -> R.drawable.ic_player_list
    PlayerButton.INFO -> R.drawable.ic_player_info
    PlayerButton.PIP -> R.drawable.ic_player_minimize
}

/**
 * TV: the picture stays clear in the middle - title on a top shade, the bar
 * and the controls on a bottom shade. Round transport buttons on the left,
 * the options as icons on the right; the one under focus turns white and
 * shows its name. Same remote order as before (Left / Right walk the row).
 */
@Composable
private fun PlayerOsdTv(uiState: PlayerUiState) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
                .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 44.dp)
        ) {
            OsdTitle(uiState, large = true)
            if (uiState.isLive) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.widthIn(max = 520.dp)) { LiveNowPlaying(uiState.liveNowPlaying) }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                .consumeTaps()
                .padding(start = 40.dp, end = 40.dp, top = 56.dp, bottom = 20.dp)
        ) {
            if (uiState.osdZone == OsdZone.EPISODES) {
                EpisodeDrawer(uiState = uiState)
                Spacer(Modifier.height(16.dp))
            }
            OsdProgress(uiState)
            Spacer(Modifier.height(10.dp))
            val buttons = uiState.playerButtons
            val touch = LocalPlayerTouch.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                buttons.forEachIndexed { index, button ->
                    val focused = uiState.osdZone == OsdZone.BUTTONS && uiState.focusedButtonIndex == index
                    // Options start after the transport group, pushed to the right.
                    if (index > 0 && !uiState.isTransport(button) && uiState.isTransport(buttons[index - 1])) {
                        Spacer(Modifier.weight(1f))
                    } else if (index > 0) {
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(Modifier.onTap { touch.onButton(index) }) {
                        if (uiState.isTransport(button)) {
                            TvRoundButton(
                                icon = playerButtonIcon(button, uiState),
                                label = playerButtonLabel(button, uiState),
                                focused = focused,
                                size = if (button == PlayerButton.PLAYPAUSE) 48.dp else 38.dp
                            )
                        } else {
                            TvOptionButton(
                                icon = playerButtonIcon(button, uiState),
                                label = playerButtonLabel(button, uiState),
                                focused = focused
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvRoundButton(icon: Int, label: String, focused: Boolean, size: androidx.compose.ui.unit.Dp) {
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.1f else 1f, label = "osdScale")
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(size * 0.42f)
        )
    }
}

/** An option: a round icon at rest, a white pill with its name under focus. */
@Composable
private fun TvOptionButton(icon: Int, label: String, focused: Boolean) {
    Row(
        modifier = Modifier
            .animateContentSize()
            .height(36.dp)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(16.dp)
        )
        if (focused) {
            Spacer(Modifier.width(6.dp))
            Text(
                label, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

/**
 * Phone / tablet: big finger-sized targets. Close and options at the top,
 * the playback controls in the middle of the picture, the bar and the
 * list at the bottom. A tap on the picture hides everything.
 */
@Composable
private fun PlayerOsdTouch(uiState: PlayerUiState) {
    val touch = LocalPlayerTouch.current
    val buttons = uiState.playerButtons
    fun tap(button: PlayerButton) {
        val index = buttons.indexOf(button)
        if (index >= 0) touch.onButton(index)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
    ) {
        // Top: close, title, options.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TouchIconButton(R.drawable.ic_lucide_arrow_left, "Fermer") { touch.onClose() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                OsdTitle(uiState, large = false)
                if (uiState.isLive) {
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.widthIn(max = 420.dp)) { LiveNowPlaying(uiState.liveNowPlaying) }
                }
            }
            buttons.filter { !uiState.isTransport(it) && it != PlayerButton.LIST }.forEach { button ->
                Spacer(Modifier.width(6.dp))
                TouchIconButton(playerButtonIcon(button, uiState), playerButtonLabel(button, uiState)) { tap(button) }
            }
        }

        // Middle: the playback controls, big.
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(22.dp)
        ) {
            buttons.filter { uiState.isTransport(it) }.forEach { button ->
                val main = button == PlayerButton.PLAYPAUSE
                Box(
                    modifier = Modifier
                        .size(if (main) 64.dp else 48.dp)
                        .background(
                            if (main) Color.White else Color.White.copy(alpha = 0.16f),
                            androidx.compose.foundation.shape.CircleShape
                        )
                        .onTap { tap(button) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(playerButtonIcon(button, uiState)),
                        contentDescription = playerButtonLabel(button, uiState),
                        tint = if (main) Color.Black else Color.White,
                        modifier = Modifier.size(if (main) 28.dp else 20.dp)
                    )
                }
            }
        }

        // Bottom: the list, the bar.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .consumeTaps()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            if (uiState.osdZone == OsdZone.EPISODES) {
                EpisodeDrawer(uiState = uiState)
                Spacer(Modifier.height(12.dp))
            }
            OsdProgress(uiState)
            if (PlayerButton.LIST in buttons) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                        .onTap { tap(PlayerButton.LIST) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(painterResource(R.drawable.ic_player_list), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(uiState.listButtonLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TouchIconButton(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Color.White.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape)
            .onTap(action = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

private fun playerButtonLabel(button: PlayerButton, uiState: PlayerUiState): String = when (button) {
    PlayerButton.PREVIOUS -> "Précédent"
    PlayerButton.REWIND -> "Reculer"
    PlayerButton.PLAYPAUSE -> if (uiState.isPlaying) "Pause" else "Lire"
    PlayerButton.FORWARD -> "Avancer"
    PlayerButton.NEXT -> "Suivant"
    PlayerButton.AUDIO -> uiState.currentAudioLabel.ifEmpty { "Audio" }
    PlayerButton.SUBTITLE -> uiState.currentSubtitleLabel
    PlayerButton.QUALITY -> uiState.currentQualityLabel
    PlayerButton.LIST -> uiState.listButtonLabel
    PlayerButton.INFO -> "Infos"
    PlayerButton.PIP -> "Réduire"
}

/** Port of Tizen's osd-episode-list (js/player.js openEpisodeList): other items in the same category/playlist. */
/** "● Direct · 18:30–21:30 Face/Off", its progress, and what comes next - or just "● Direct" without a guide. */
@Composable
private fun LiveNowPlaying(program: LiveProgram?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("● Direct", color = BtvGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (program != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "${formatClock(program.startMs)}–${formatClock(program.endMs)}",
                color = Color(0xFFCCCCCC), fontSize = 12.sp
            )
            Spacer(Modifier.width(6.dp))
            Text(program.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (program == null) return
    // Recomputed every 30 s: the bar keeps moving while the OSD stays open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(program) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(program.progress(now))
                .fillMaxHeight()
                .background(BtvGreen, RoundedCornerShape(2.dp))
        )
    }
    program.nextTitle?.let { next ->
        Spacer(Modifier.height(3.dp))
        Text(
            "Ensuite" + (program.nextStartMs?.let { " à ${formatClock(it)}" } ?: "") + " : $next",
            color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatClock(timeMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(timeMs))

@Composable
private fun EpisodeDrawer(uiState: PlayerUiState) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(uiState.episodeFocusIndex) {
        // Keep the focused row in the middle of the 3 visible rows (list end clamps automatically).
        try { listState.animateScrollToItem(maxOf(0, uiState.episodeFocusIndex - 1)) } catch (e: IllegalStateException) {}
    }
    val touch = LocalPlayerTouch.current
    // TV: 3 rows; touch: 4 taller rows, easy to hit.
    val isTv = com.btv.ui.theme.LocalIsTv.current
    val thumb = if (isTv) 28.dp else 34.dp
    val rowPadding = if (isTv) 6.dp else 8.dp
    val rows = if (isTv) 3 else 4
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height((thumb + rowPadding * 2 + 2.dp) * rows + 12.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(uiState.zapList) { index, item ->
                val isFocused = index == uiState.episodeFocusIndex
                val isPlaying = index == uiState.zapIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onTap { touch.onEpisode(index) }
                        .padding(bottom = 2.dp)
                        .background(if (isFocused) Color.White.copy(alpha = 0.16f) else Color.Transparent, com.btv.ui.theme.BtvShapes.control)
                        .border(2.dp, if (isFocused) com.btv.ui.theme.BtvTheme.colors.focusRing else Color.Transparent, com.btv.ui.theme.BtvShapes.control)
                        .padding(horizontal = 8.dp, vertical = rowPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.name,
                        modifier = Modifier.width(thumb).height(thumb).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2a2a2a))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = com.btv.util.displayTitle(item.name) + if (isPlaying) "  ●" else "",
                        color = if (isPlaying) BtvGreenBright else Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    uiState.zapPrograms[item.id]?.let { program ->
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${formatClock(program.startMs)}–${formatClock(program.endMs)}  ${program.title}",
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMenuOverlay(uiState: PlayerUiState) {
    val options = uiState.trackMenuOptions()
    val touch = LocalPlayerTouch.current
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .padding(end = 40.dp)
                .consumeTaps()
                .background(com.btv.ui.theme.BtvSurface, com.btv.ui.theme.BtvShapes.panel)
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
                Text("Aucune piste disponible.", color = Color(0xFF999999), fontSize = 14.sp)
            }
            options.forEachIndexed { index, option ->
                val isFocused = index == uiState.trackMenuFocusIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onTap { touch.onTrackOption(index) }
                        .padding(bottom = 4.dp)
                        .background(if (isFocused) Color.White.copy(alpha = 0.16f) else Color.Transparent, com.btv.ui.theme.BtvShapes.control)
                        .border(2.dp, if (isFocused) com.btv.ui.theme.BtvTheme.colors.focusRing else Color.Transparent, com.btv.ui.theme.BtvShapes.control)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        (if (option.isSelected) "✓ " else "") + option.label,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ExitPlayerDialog(focusIndex: Int) {
    val touch = LocalPlayerTouch.current
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .shadow(15.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .background(com.btv.ui.theme.BtvSurface, com.btv.ui.theme.BtvShapes.panel)
                .width(280.dp)
                .consumeTaps()
                .padding(16.dp)
        ) {
            Text("Quitter la lecture ?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column {
                ResumeDialogButton("Réduire (mini-lecteur)", isFocused = focusIndex == 0, onClick = { touch.onExitChoice(0) }, fillWidth = true)
                Spacer(Modifier.height(8.dp))
                ResumeDialogButton("Sortir", isFocused = focusIndex == 1, onClick = { touch.onExitChoice(1) }, fillWidth = true)
            }
        }
    }
}

/**
 * Progress bar of the OSD. While the remote is on it (seek zone) a round
 * thumb sits on the playhead, so it is obvious Left/Right will seek.
 */
@Composable
private fun SeekBar(fraction: Float, focused: Boolean, modifier: Modifier = Modifier) {
    val thumbSize = 12.dp
    val touch = LocalPlayerTouch.current
    val isTv = com.btv.ui.theme.LocalIsTv.current
    var dragFraction by remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: fraction
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> touch.onSeekFraction(offset.x / size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragFraction?.let(touch.onSeekFraction); dragFraction = null },
                    onDragCancel = { dragFraction = null }
                ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            // After the gesture handlers: off-TV the finger gets a taller target than the bar.
            .then(if (isTv) Modifier else Modifier.padding(vertical = 10.dp))
            .height(thumbSize),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 4.dp else 3.dp)
                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .background(BtvGreen, RoundedCornerShape(2.dp))
            )
        }
        if (focused || dragFraction != null) {
            Box(
                modifier = Modifier
                    .offset(x = (maxWidth * shown - thumbSize / 2).coerceIn(0.dp, maxWidth - thumbSize))
                    .size(thumbSize)
                    .shadow(4.dp, androidx.compose.foundation.shape.CircleShape)
                    .background(Color.White, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}

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
                .shadow(15.dp, com.btv.ui.theme.BtvShapes.dialog, ambientColor = Color.Black, spotColor = Color.Black)
                .background(com.btv.ui.theme.BtvSurface, com.btv.ui.theme.BtvShapes.dialog)
                .border(1.dp, Color.White.copy(alpha = 0.1f), com.btv.ui.theme.BtvShapes.dialog)
                .width(460.dp)
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
                .padding(horizontal = 28.dp, vertical = 24.dp)
        ) {
            Text(com.btv.util.displayTitle(title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(
                "Reprendre à ${formatTime(resumePositionMs)} ?",
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(20.dp))
            Row {
                ResumeDialogButton("Reprendre", isFocused = focusIndex == 0, onClick = onResume, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ResumeDialogButton("Recommencer", isFocused = focusIndex == 1, onClick = onRestart, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Sans réponse, la lecture repart du début dans ${remainingSeconds} s.",
                color = Color(0xFF999999),
                fontSize = 12.sp
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
                .background(com.btv.ui.theme.BtvSurface, com.btv.ui.theme.BtvShapes.panel)
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
            Text("Lire la saison suivante ?", color = Color(0xFFCCCCCC), fontSize = 14.sp)
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
                fontSize = 12.sp
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
            .background(if (isFocused) BtvGreen else Color.White.copy(alpha = 0.1f), com.btv.ui.theme.BtvShapes.control)
            .border(2.dp, if (isFocused) Color.White else Color.Transparent, com.btv.ui.theme.BtvShapes.control)
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (isFocused) com.btv.ui.theme.BtvTheme.colors.onAccent else Color.White,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
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
                fontSize = 14.sp,
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
                fontSize = 14.sp
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
