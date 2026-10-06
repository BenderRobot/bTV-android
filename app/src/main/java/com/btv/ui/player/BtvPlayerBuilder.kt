package com.btv.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * After a stall, wait for a solid cushion before resuming: resuming on
 * ExoPlayer's default 5s only to stall again a moment later is the
 * stutter loop that makes a weak line unwatchable.
 */
private const val BUFFER_AFTER_REBUFFER_MS = 8_000

/**
 * ExoPlayer wired for unreliable IPTV panels: live TS connections are
 * transparently reopened (see [ResilientLiveDataSource]), cross-protocol
 * redirects are followed (panels often bounce http <-> https to an edge
 * server), and a stalled live load fails fast to the ViewModel, which knows
 * the sibling qualities to fall back to.
 */
@OptIn(UnstableApi::class)
fun buildBtvExoPlayer(context: Context): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(8_000)
        .setAllowCrossProtocolRedirects(true)
    val dataSourceFactory = ResilientLiveDataSource.Factory(DefaultDataSource.Factory(context, httpFactory))
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        .setLoadErrorHandlingPolicy(LiveAwareLoadErrorPolicy())
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            BUFFER_AFTER_REBUFFER_MS
        )
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .build()
}

/**
 * A live TS load reaching ExoPlayer's error path means
 * [ResilientLiveDataSource] already spent its reconnection window - an
 * ExoPlayer-level retry would only reopen at a byte offset the panel
 * ignores. Surface it at once so the ViewModel switches variant.
 */
@OptIn(UnstableApi::class)
private class LiveAwareLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isXtreamLiveTsPath(loadErrorInfo.loadEventInfo.dataSpec.uri.path)) C.TIME_UNSET
        else super.getRetryDelayMsFor(loadErrorInfo)
}
