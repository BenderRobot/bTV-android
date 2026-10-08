package com.btv.ui.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * An Xtream live channel is one endless TS response. When that connection
 * drops, ExoPlayer would either fail the whole item (black screen, full
 * reload) or retry with a Range request a live panel cannot honour. This
 * source instead reopens the same URL from scratch and keeps feeding the
 * extractor as if nothing happened: TS resyncs on its own packet boundaries
 * and the audio sink absorbs the timestamp jump, so what is already buffered
 * keeps playing while the link comes back.
 *
 * Anything that is not a live TS URL (VOD, series, HLS, local files) passes
 * straight through.
 */
@OptIn(UnstableApi::class)
class ResilientLiveDataSource(
    private val upstreamFactory: DataSource.Factory,
    /** Catch-up archives: a panel may take long to seek one before the first byte. */
    private val timeshiftFactory: DataSource.Factory = upstreamFactory,
    private val isLiveUri: (Uri) -> Boolean = { isXtreamLiveTsPath(it.path) },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val sleepMs: (Long) -> Unit = Thread::sleep
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val timeshiftFactory: DataSource.Factory = upstreamFactory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ResilientLiveDataSource(upstreamFactory, timeshiftFactory)
    }

    private val transferListeners = mutableListOf<TransferListener>()
    private var upstream: DataSource? = null
    private var liveSpec: DataSpec? = null

    /** Silent reconnections since open() - exposed for tests and logs. */
    var reconnectCount = 0
        private set

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    // Drop handling spans read() calls: a panel that accepts the connection
    // and closes it at once (connection limit reached, empty body) must not
    // be hammered with instant reconnects - only real bytes end an outage.
    private var outageDeadlineMs = 0L
    private var emptyReconnects = 0

    override fun open(dataSpec: DataSpec): Long {
        reconnectCount = 0
        outageDeadlineMs = 0L
        emptyReconnects = 0
        if (!isLiveUri(dataSpec.uri)) {
            liveSpec = null
            val factory = if (isTimeshiftPath(dataSpec.uri.path)) timeshiftFactory else upstreamFactory
            val source = newUpstream(factory)
            upstream = source
            return source.open(dataSpec)
        }
        // A live panel always starts "now": never ask it for a byte offset.
        val spec = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        liveSpec = spec
        connect(spec, deadlineMs = nowMs() + INITIAL_CONNECT_WINDOW_MS, initial = true)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val spec = liveSpec ?: return checkNotNull(upstream).read(buffer, offset, length)
        while (true) {
            val source = upstream
            if (source != null) {
                try {
                    val read = source.read(buffer, offset, length)
                    // A live channel never "ends": a clean EOF is a server-side drop.
                    if (read != C.RESULT_END_OF_INPUT) {
                        if (read > 0) {
                            outageDeadlineMs = 0L
                            emptyReconnects = 0
                        }
                        return read
                    }
                    android.util.Log.w(TAG, "Live stream ended by server, reconnecting")
                } catch (error: IOException) {
                    throwIfCancelled()
                    android.util.Log.w(TAG, "Live stream dropped (${error.javaClass.simpleName}), reconnecting")
                }
                closeQuietly(source)
                upstream = null
            }
            val now = nowMs()
            if (outageDeadlineMs == 0L) outageDeadlineMs = now + RECONNECT_WINDOW_MS
            else if (now >= outageDeadlineMs) throw IOException("Live stream kept dropping for ${RECONNECT_WINDOW_MS}ms")
            if (emptyReconnects > 0) {
                // The previous reconnection gave nothing: wait before the next.
                val waitMs = BACKOFF_MS[(emptyReconnects - 1).coerceAtMost(BACKOFF_MS.size - 1)]
                if (now + waitMs >= outageDeadlineMs) throw IOException("Live stream kept dropping for ${RECONNECT_WINDOW_MS}ms")
                pause(waitMs)
            }
            connect(spec, outageDeadlineMs, initial = false)
            reconnectCount++
            emptyReconnects++
        }
    }

    override fun getUri(): Uri? = upstream?.uri ?: liveSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = upstream
        upstream = null
        liveSpec = null
        source?.close()
    }

    private fun connect(spec: DataSpec, deadlineMs: Long, initial: Boolean) {
        var attempt = 0
        while (true) {
            throwIfCancelled()
            val source = newUpstream()
            try {
                source.open(spec)
                upstream = source
                return
            } catch (error: IOException) {
                closeQuietly(source)
                throwIfCancelled()
                // Unknown channel or rejected credentials won't fix themselves:
                // fail fast so the player moves on to another quality.
                if (initial && error.isPermanent()) throw error
                // 403 is retried on purpose: a panel limiting connections
                // keeps counting the dead socket for a few seconds.
                val waitMs = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.size - 1)]
                attempt++
                if (nowMs() + waitMs > deadlineMs) throw error
                pause(waitMs)
            }
        }
    }

    private fun pause(waitMs: Long) {
        try {
            sleepMs(waitMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Live reconnection cancelled")
        }
    }

    private fun newUpstream(factory: DataSource.Factory = upstreamFactory): DataSource =
        factory.createDataSource().also { source -> transferListeners.forEach(source::addTransferListener) }

    // The Loader interrupts its thread when a load is cancelled (zap, stop,
    // release): never keep reconnecting for media nobody plays any more.
    private fun throwIfCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Live load cancelled")
    }

    private fun IOException.isPermanent(): Boolean =
        this is HttpDataSource.InvalidResponseCodeException && responseCode in PERMANENT_HTTP_CODES

    private fun closeQuietly(source: DataSource) {
        try {
            source.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val TAG = "BtvLiveSource"
        /** First connection: short, a dead variant must hand over to the next one quickly. */
        const val INITIAL_CONNECT_WINDOW_MS = 8_000L
        /** Mid-stream drop: keep trying while the buffer covers the gap, then let the player switch variant. */
        const val RECONNECT_WINDOW_MS = 20_000L
        private val BACKOFF_MS = longArrayOf(250L, 500L, 1_000L, 1_500L, 2_000L)
        private val PERMANENT_HTTP_CODES = setOf(400, 401, 404, 410)
    }
}
