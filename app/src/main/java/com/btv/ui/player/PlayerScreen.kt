package com.btv.ui.player

import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
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

    // A phone held upright gets the portrait layout (never a TV).
    val isTv = com.btv.ui.theme.LocalIsTv.current
    val portrait = !isTv &&
        androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    // The rest of the app stays landscape; the player follows the phone
    // (respecting its rotation lock). "Plein écran" forces landscape until
    // its exit button, or until the player closes.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var forcedLandscape by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    DisposableEffect(activity, isTv, forcedLandscape) {
        if (!isTv) {
            activity?.requestedOrientation = if (forcedLandscape) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        }
        // Leaving the player, the next screen's own orientation is set by BtvApp (MainActivity).
        onDispose { }
    }
    // Upright, nothing floats over the picture: Back then goes straight to
    // "Réduire / Sortir" instead of first hiding controls nobody sees.
    LaunchedEffect(portrait, uiState.osdVisible) { if (portrait) viewModel.hideControls() }

    val orientation = remember(forcedLandscape) {
        OrientationControl(forcedLandscape) { forcedLandscape = !forcedLandscape }
    }

    // Upright phone: the list under the picture ("Infos" scrolls it to the details).
    val portraitList = androidx.compose.foundation.lazy.rememberLazyListState()
    // Its controls over the picture: always while paused; while playing, after
    // a tap, for 3 s from the last touch.
    var portraitControls by remember { mutableStateOf(true) }
    var portraitTouchedAt by remember { mutableLongStateOf(0L) }
    // Swipe down on the picture: 0 = in place, 1 = shrunk in the bottom-right
    // corner (where the mini-player appears). The picture follows the finger.
    val minimizeProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    val showPortraitControls = (portraitControls || !uiState.isPlaying) && minimizeProgress.value == 0f
    LaunchedEffect(portraitControls, uiState.isPlaying, portraitTouchedAt) {
        if (portraitControls && uiState.isPlaying) {
            delay(3_000)
            portraitControls = false
        }
    }

    // The Infos panel scrolls with Up / Down; it opens at its top.
    val infoScroll = androidx.compose.foundation.rememberScrollState()
    val infoScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(uiState.infoVisible) { if (uiState.infoVisible) infoScroll.scrollTo(0) }

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
    androidx.compose.runtime.CompositionLocalProvider(LocalPlayerTouch provides touch, LocalOrientationControl provides orientation) {
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
                if (uiState.infoVisible && (keyEvent.key == Key.DirectionUp || keyEvent.key == Key.DirectionDown)) {
                    val step = if (keyEvent.key == Key.DirectionDown) 260f else -260f
                    infoScope.launch { infoScroll.animateScrollBy(step) }
                    return@onKeyEvent true
                }
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
        // Phone held upright: the picture on top at 16:9, details under it.
        Column(Modifier.fillMaxSize()) {
        Box(
            modifier = if (portrait) {
                val screenHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                    androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
                }
                val marginPx = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
                // The full swipe: about 60 % of the screen's height.
                val swipeRangePx = screenHeightPx * 0.6f
                // Read at the end of the swipe: the row may have changed since it started.
                val latestButtons by androidx.compose.runtime.rememberUpdatedState(uiState.playerButtons)
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .aspectRatio(16f / 9f)
                    // Measured before the transform: the finger, not the moving picture.
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                infoScope.launch {
                                    // Past a quarter: it goes on into the corner and becomes
                                    // the mini-player; otherwise it springs back.
                                    if (minimizeProgress.value > 0.25f) {
                                        minimizeProgress.animateTo(1f, androidx.compose.animation.core.tween(160))
                                        latestButtons.indexOf(PlayerButton.PIP).takeIf { it >= 0 }?.let(touch.onButton)
                                    } else {
                                        minimizeProgress.animateTo(0f, androidx.compose.animation.core.spring())
                                    }
                                }
                            },
                            onDragCancel = { infoScope.launch { minimizeProgress.animateTo(0f) } }
                        ) { change, amount ->
                            change.consume()
                            infoScope.launch {
                                minimizeProgress.snapTo((minimizeProgress.value + amount / swipeRangePx).coerceIn(0f, 1f))
                            }
                        }
                    }
                    // Shrinks to 45 % towards the bottom right as the finger goes down.
                    .graphicsLayer {
                        val p = minimizeProgress.value
                        val scale = 1f - 0.55f * p
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                        translationY = p * (screenHeightPx - size.height * scale - marginPx).coerceAtLeast(0f)
                        clip = p > 0f
                        shape = RoundedCornerShape((12 * p).dp)
                    }
            } else Modifier.fillMaxSize()
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

        if (portrait) {
            // YouTube-like: the controls float over the picture while paused or
            // for a few seconds after a tap, then fade away while it plays. Close
            // and the options on top, playback in the middle, time and full
            // screen at the bottom. A swipe down shrinks the video into the
            // mini-player and goes back to the previous screen.
            fun tapButton(button: PlayerButton) {
                portraitTouchedAt = System.currentTimeMillis()
                uiState.playerButtons.indexOf(button).takeIf { it >= 0 }?.let(touch.onButton)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            portraitControls = !portraitControls
                            portraitTouchedAt = System.currentTimeMillis()
                        }
                    }
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = showPortraitControls,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VideoOverlayButton(R.drawable.ic_lucide_arrow_left, "Fermer") { touch.onClose() }
                        Spacer(Modifier.weight(1f))
                        listOf(PlayerButton.AUDIO, PlayerButton.SUBTITLE, PlayerButton.QUALITY, PlayerButton.INFO, PlayerButton.PIP)
                            .filter { it in uiState.playerButtons && (it != PlayerButton.INFO || !uiState.isLive) }
                            .forEach { button ->
                                VideoOverlayButton(playerButtonIcon(button, uiState), playerButtonLabel(button, uiState)) {
                                    // Details are right under the picture: Infos scrolls down to them.
                                    if (button == PlayerButton.INFO) infoScope.launch {
                                        portraitList.animateScrollToItem((portraitList.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                    } else tapButton(button)
                                }
                            }
                    }
                    // Playback in the middle, see-through like the rest.
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        uiState.playerButtons.filter { uiState.isTransport(it) }.forEach { button ->
                            val main = button == PlayerButton.PLAYPAUSE
                            Box(
                                modifier = Modifier
                                    .size(if (main) 56.dp else 40.dp)
                                    .background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape)
                                    .onTap { tapButton(button) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painterResource(playerButtonIcon(button, uiState)),
                                    contentDescription = playerButtonLabel(button, uiState),
                                    tint = Color.White,
                                    modifier = Modifier.size(if (main) 28.dp else 18.dp)
                                )
                            }
                        }
                    }
                    // Time at the bottom left, full screen at the bottom right.
                    Text(
                        when {
                            uiState.isLive && !uiState.isSeekable -> uiState.liveNowPlaying?.let {
                                "● Direct  " + formatClock(it.startMs) + " – " + formatClock(it.endMs)
                            } ?: "● Direct"
                            uiState.duration > 0 -> formatTime(uiState.currentPosition) + " / " + formatTime(uiState.duration)
                            else -> ""
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp)
                    )
                    Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                        VideoOverlayButton(R.drawable.ic_player_fullscreen, "Plein écran") { orientation.toggle() }
                    }
                }
            }
        }

        uiState.flashMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (portrait) 12.dp else 40.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(message, color = Color.White, fontSize = 14.sp)
            }
        }
        }
        if (portrait) {
            // The bar on the edge between the picture and the rest, as on YouTube.
            // While the picture is swiped down, the page under it fades and sinks.
            val sinking = Modifier.graphicsLayer {
                val p = minimizeProgress.value
                alpha = (1f - p * 2.5f).coerceAtLeast(0f)
                translationY = p * 160.dp.toPx()
            }
            Box(sinking) { PortraitEdgeBar(uiState, showThumb = showPortraitControls) }
            PortraitPlayerDetails(
                uiState = uiState,
                listState = portraitList,
                onLoadInfo = viewModel::ensureInfoLoaded,
                modifier = Modifier.weight(1f).then(sinking)
            )
        }
        }

        if (!portrait && uiState.osdVisible && !uiState.infoVisible && resumePrompt == null && nextSeasonPrompt == null) {
            PlayerOsd(uiState = uiState)
        }

        if (uiState.trackMenuType != null) {
            TrackMenuOverlay(uiState = uiState)
        }

        if (uiState.infoVisible && !portrait) {
            InfoPanel(uiState = uiState, scrollState = infoScroll, modifier = Modifier.align(Alignment.CenterEnd))
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun InfoPanel(uiState: PlayerUiState, scrollState: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier) {
    val info = uiState.info
    val episodeLine = uiState.seriesName?.let { com.btv.util.displayTitle(uiState.contentName) }
    val title = uiState.seriesName?.let { com.btv.util.displayTitle(it) } ?: com.btv.util.displayTitle(uiState.contentName)
    val muted = Color.White.copy(alpha = 0.6f)
    val shape = RoundedCornerShape(16.dp)
    val isTv = com.btv.ui.theme.LocalIsTv.current
    // A wide card on the right: whole poster top-left, the details beside it,
    // then the synopsis and the cast faces. Up / Down (or a finger) scroll it.
    Column(
        modifier = modifier
            .padding(end = 28.dp)
            .fillMaxWidth(0.6f)
            .fillMaxHeight(0.9f)
            .clip(shape)
            .background(Color(0xFF0A0A0A).copy(alpha = 0.88f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .verticalScroll(scrollState)
            .padding(22.dp)
    ) {
        Row {
            info?.posterUrl?.let { poster ->
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    // The whole poster, never cropped.
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    alignment = Alignment.TopStart,
                    modifier = Modifier
                        .width(150.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
                episodeLine?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                }
                if (info != null && !uiState.isLive) {
                    val meta = listOfNotNull(info.rating?.let { "★ $it" }) + info.meta
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    info.director?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("Réalisation", color = muted, fontSize = 12.sp)
                        Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                    }
                }
            }
        }

        when {
            uiState.isLive -> {
                val now = uiState.liveNowPlaying
                Spacer(Modifier.height(14.dp))
                if (now != null) {
                    Text("${formatClock(now.startMs)}–${formatClock(now.endMs)}", color = BtvGreenBright, fontSize = 13.sp)
                    Text(now.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    now.nextTitle?.let { next ->
                        Spacer(Modifier.height(10.dp))
                        val at = now.nextStartMs?.let { " à ${formatClock(it)}" }.orEmpty()
                        Text("Ensuite$at", color = muted, fontSize = 12.sp)
                        Text(next, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    }
                } else {
                    Text("Pas de programme annoncé pour cette chaîne.", color = muted, fontSize = 14.sp)
                }
            }
            uiState.isInfoLoading -> {
                Spacer(Modifier.height(14.dp))
                Text("Chargement des informations…", color = muted, fontSize = 14.sp)
            }
            info == null -> {
                Spacer(Modifier.height(14.dp))
                Text("Aucune information disponible pour ce titre.", color = muted, fontSize = 14.sp)
            }
            else -> {
                info.plot?.let {
                    Spacer(Modifier.height(18.dp))
                    Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 22.sp)
                }
                if (info.castPhotos.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text("Casting", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
                    ) {
                        info.castPhotos.forEach { person ->
                            com.btv.ui.browse.components.CastAvatar(person, modifier = Modifier.width(84.dp), size = 64.dp)
                        }
                    }
                } else {
                    info.cast?.let {
                        Spacer(Modifier.height(16.dp))
                        Text("Avec", color = muted, fontSize = 12.sp)
                        Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
        if (isTv) {
            Spacer(Modifier.height(18.dp))
            Text("Haut / Bas pour faire défiler · OK ou Retour pour fermer", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
        }
    }
}

/**
 * Phone held upright: everything under the 16:9 picture, nothing over it -
 * title, bar, controls, options, then the episodes and the film's details,
 * scrolling together. Turn the phone and the full-screen player comes back.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PortraitPlayerDetails(
    uiState: PlayerUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onLoadInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val touch = LocalPlayerTouch.current
    val buttons = uiState.playerButtons
    fun tap(button: PlayerButton) {
        val index = buttons.indexOf(button)
        if (index >= 0) touch.onButton(index)
    }
    LaunchedEffect(uiState.contentName, uiState.isLive) { if (!uiState.isLive) onLoadInfo() }
    val muted = Color.White.copy(alpha = 0.6f)

    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().consumeTaps(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        item {
            Column {
                OsdTitle(uiState, large = false)
                if (uiState.isLive) {
                    Spacer(Modifier.height(6.dp))
                    LiveNowPlaying(uiState.liveNowPlaying)
                }
                // Bar and controls are on the picture now (YouTube-like).
            }
        }

        // The other episodes / channels of the list.
        if (uiState.zapList.size > 1) {
            item {
                Spacer(Modifier.height(22.dp))
                Text(uiState.listButtonLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(uiState.zapList) { index, item ->
                val isPlaying = index == uiState.zapIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .background(if (isPlaying) Color.White.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .onTap { touch.onEpisode(index) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2a2a2a))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            com.btv.util.displayTitle(item.name),
                            color = if (isPlaying) BtvGreenBright else Color.White,
                            fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        uiState.zapPrograms[item.id]?.let { program ->
                            Text(
                                "${formatClock(program.startMs)}–${formatClock(program.endMs)}  ${program.title}",
                                color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (isPlaying) {
                        Spacer(Modifier.width(8.dp))
                        Text("En cours", color = BtvGreenBright, fontSize = 12.sp)
                    }
                }
            }
        }

        // The details of the film / episode (what the "Infos" panel shows).
        if (!uiState.isLive) {
            item {
                val info = uiState.info
                Spacer(Modifier.height(22.dp))
                when {
                    uiState.isInfoLoading -> Text("Chargement des informations…", color = muted, fontSize = 13.sp)
                    info == null -> Unit
                    else -> Column {
                        Row {
                            info.posterUrl?.let { poster ->
                                AsyncImage(
                                    model = poster,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    alignment = Alignment.TopStart,
                                    modifier = Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                val meta = listOfNotNull(info.rating?.let { "★ $it" }) + info.meta
                                if (meta.isNotEmpty()) {
                                    Text(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 19.sp)
                                }
                                info.director?.let {
                                    Spacer(Modifier.height(10.dp))
                                    Text("Réalisation", color = muted, fontSize = 12.sp)
                                    Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                                }
                            }
                        }
                        info.plot?.let {
                            Spacer(Modifier.height(14.dp))
                            Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 21.sp)
                        }
                        if (info.castPhotos.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            Text("Casting", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                            ) {
                                info.castPhotos.forEach { person ->
                                    com.btv.ui.browse.components.CastAvatar(person, modifier = Modifier.width(76.dp), size = 58.dp)
                                }
                            }
                        } else {
                            info.cast?.let {
                                Spacer(Modifier.height(14.dp))
                                Text("Avec", color = muted, fontSize = 12.sp)
                                Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
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

/** Phone only: whether "Plein écran" holds the player in landscape, and the switch. */
private class OrientationControl(val forcedLandscape: Boolean, val toggle: () -> Unit)

private val LocalOrientationControl = androidx.compose.runtime.compositionLocalOf<OrientationControl?> { null }

@Composable
private fun PlayerOsd(uiState: PlayerUiState) {
    if (com.btv.ui.theme.LocalIsTv.current) PlayerOsdTv(uiState) else PlayerOsdTouch(uiState)
}

/** Series name large, then the episode; a film or a channel: its title alone. */
@Composable
private fun OsdTitle(uiState: PlayerUiState, large: Boolean) {
    val seriesName = uiState.seriesName?.let { com.btv.util.displayTitle(it) }
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

/**
 * Position on the bar, then the times under it at both ends. A live channel
 * shows its programme the same way: how far it is, its start and end times.
 */
@Composable
private fun OsdProgress(uiState: PlayerUiState) {
    if (uiState.isLive && !uiState.isSeekable) {
        uiState.liveNowPlaying?.let { LiveProgramProgress(it) }
        return
    }
    if (uiState.duration <= 0) return
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

/** The live programme on the bottom bar: not seekable, it just moves with the clock. */
@Composable
private fun LiveProgramProgress(program: LiveProgram) {
    // Recomputed every 30 s: the bar keeps moving while the controls stay open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(program) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        // Same height and place as the seek bar of a film.
        Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(program.progress(now))
                        .fillMaxHeight()
                        .background(BtvGreen, RoundedCornerShape(2.dp))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(program.startMs), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(formatClock(program.endMs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
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
            // The list opens under the controls (Down from the buttons).
            if (uiState.osdZone == OsdZone.EPISODES) {
                Spacer(Modifier.height(14.dp))
                EpisodeDrawer(uiState = uiState)
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
            // Held in landscape by "Plein écran": the way back to the upright layout.
            LocalOrientationControl.current?.takeIf { it.forcedLandscape }?.let { control ->
                Spacer(Modifier.width(6.dp))
                TouchIconButton(R.drawable.ic_player_fullscreen_exit, "Quitter le plein écran") { control.toggle() }
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
            if (uiState.osdZone == OsdZone.EPISODES) {
                Spacer(Modifier.height(10.dp))
                EpisodeDrawer(uiState = uiState)
            }
        }
    }
}

/**
 * Upright phone: the progress bar right on the picture's bottom edge (its
 * touch area overlaps the picture). A live channel shows its programme.
 */
@Composable
private fun PortraitEdgeBar(uiState: PlayerUiState, showThumb: Boolean) {
    when {
        uiState.isLive && !uiState.isSeekable -> uiState.liveNowPlaying?.let { program ->
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(program) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(30_000)
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(program.progress(now)).fillMaxHeight().background(BtvGreen))
            }
        }
        uiState.duration > 0 -> SeekBar(
            fraction = (uiState.currentPosition.toFloat() / uiState.duration).coerceIn(0f, 1f),
            focused = showThumb,
            // Its 10 dp finger margin above the line goes over the picture.
            modifier = Modifier.fillMaxWidth().offset(y = (-10).dp)
        )
    }
}

/** A small see-through button on the picture (upright phone). */
@Composable
private fun VideoOverlayButton(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape)
            .onTap(action = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
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
    // Some streams name their track with symbols only ("```"): "Audio" then.
    PlayerButton.AUDIO -> uiState.currentAudioLabel.takeIf { label -> label.any { it.isLetterOrDigit() } } ?: "Audio"
    PlayerButton.SUBTITLE -> uiState.currentSubtitleLabel
    PlayerButton.QUALITY -> uiState.currentQualityLabel
    PlayerButton.LIST -> uiState.listButtonLabel
    PlayerButton.INFO -> "Infos"
    PlayerButton.PIP -> "Réduire"
}

/** Port of Tizen's osd-episode-list (js/player.js openEpisodeList): other items in the same category/playlist. */
/**
 * "● Direct  Face/Off" and what comes next - or just "● Direct" without a
 * guide. The programme's times and progress are on the bottom bar (OsdProgress).
 */
@Composable
private fun LiveNowPlaying(program: LiveProgram?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("● Direct", color = BtvGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (program != null) {
            Spacer(Modifier.width(8.dp))
            Text(program.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (program == null) return
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
