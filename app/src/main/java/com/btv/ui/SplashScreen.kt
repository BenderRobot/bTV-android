package com.btv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import android.view.ViewGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun SplashScreen(onFinished: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Upright phone: its own vertical intro (the 16:9 one would leave most of the screen black).
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    var playbackFailed by remember { mutableStateOf(false) }
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            val intro = if (portrait) com.btv.R.raw.btv_splash_portrait else com.btv.R.raw.btv_splash
            setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(intro)))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed = true
                }
            })
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(Unit) {
        delay(10000)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!playbackFailed) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        this.player = player
                    }
                },
                // Each intro matches its screen's shape: it fills it.
                update = { it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
