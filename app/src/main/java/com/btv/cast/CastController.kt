package com.btv.cast

import android.content.Context
import android.util.Log
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the phone hands to a Chromecast: the stream itself, its name, where to start. */
data class CastRequest(
    val url: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val isLive: Boolean,
    val positionMs: Long
)

data class CastState(
    /** A Chromecast / Google TV is on the Wi-Fi: the cast icon shows. */
    val available: Boolean = false,
    /** Connected to this device (its name), or null. */
    val deviceName: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** The receiver could not play the stream (format, network...). */
    val error: String? = null
) {
    val connected: Boolean get() = deviceName != null
}

/**
 * Google Cast on phones and tablets (never on a TV, which has no Google Play
 * services on Fire OS). The Chromecast plays the IPTV stream itself with the
 * default receiver: the phone only sends the address and becomes a remote.
 */
object CastController {
    private const val TAG = "BtvCast"

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    // Both are the SDKs' own process-wide singletons, built on the application
    // context (never an Activity): holding them here leaks nothing.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var castContext: CastContext? = null
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var router: MediaRouter? = null
    private var discovering = 0

    private val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshAvailability()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshAvailability()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshAvailability()
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = refreshPlayback()
    }

    private val progressListener = RemoteMediaClient.ProgressListener { progress, duration ->
        _state.update { it.copy(positionMs = progress, durationMs = duration) }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) = detach()
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
    }

    /** Once at start-up, phones only. Without Google Play services nothing happens. */
    fun init(context: Context) {
        if (castContext != null) return
        val app = context.applicationContext
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) return
        castContext = try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(app)
        } catch (error: Exception) {
            Log.w(TAG, "Cast unavailable: ${error.javaClass.simpleName}")
            null
        } ?: return
        router = MediaRouter.getInstance(app)
        castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        castContext?.sessionManager?.currentCastSession?.let(::attach)
    }

    /** While the player is shown: look for devices on the Wi-Fi. */
    fun startDiscovery() {
        val router = router ?: return
        if (discovering++ == 0) {
            router.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        }
        refreshAvailability()
    }

    fun stopDiscovery() {
        val router = router ?: return
        if (discovering > 0 && --discovering == 0) router.removeCallback(routerCallback)
    }

    private fun refreshAvailability() {
        val router = router ?: return
        val available = router.isRouteAvailable(selector, MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE)
        _state.update { it.copy(available = available || it.connected) }
    }

    /** The cast icon: Android's device list, or the connected device's controls. */
    fun showDevicePicker(context: Context) {
        if (castContext == null) return
        if (_state.value.connected) {
            MediaRouteControllerDialog(context).show()
        } else {
            MediaRouteChooserDialog(context).apply { routeSelector = selector }.show()
        }
    }

    private fun attach(session: CastSession) {
        val client = session.remoteMediaClient
        client?.registerCallback(mediaCallback)
        client?.addProgressListener(progressListener, 1_000L)
        _state.update { it.copy(deviceName = session.castDevice?.friendlyName ?: "Chromecast", available = true, error = null) }
        refreshPlayback()
    }

    private fun detach() {
        _state.update { CastState(available = it.available) }
        refreshAvailability()
    }

    private fun client(): RemoteMediaClient? = castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    private fun refreshPlayback() {
        val client = client() ?: return
        val idleError = client.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE &&
            client.idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR
        _state.update {
            it.copy(
                playing = client.isPlaying || client.isBuffering,
                error = if (idleError) "La TV n'a pas pu lire ce flux (format non pris en charge ?)" else it.error
            )
        }
    }

    /** Sends [request] to the connected device. */
    fun load(request: CastRequest) {
        val client = client() ?: return
        val metadata = MediaMetadata(if (request.isLive) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, request.title)
            request.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            request.posterUrl?.let { addImage(WebImage(android.net.Uri.parse(it))) }
        }
        val media = MediaInfo.Builder(request.url)
            .setStreamType(if (request.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentTypeOf(request.url))
            .setMetadata(metadata)
            .build()
        _state.update { it.copy(error = null) }
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(media)
                .setAutoplay(true)
                .setCurrentTime(if (request.isLive) 0L else request.positionMs)
                .build()
        )
    }

    fun togglePlay() {
        client()?.togglePlayback()
    }

    fun seekBy(deltaMs: Long) {
        val client = client() ?: return
        val target = (client.approximateStreamPosition + deltaMs).coerceAtLeast(0L)
        client.seek(MediaSeekOptions.Builder().setPosition(target).build())
    }

    fun seekTo(positionMs: Long) {
        client()?.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0L)).build())
    }

    /** Stops casting; the last position is kept in [state] for the phone to resume from. */
    fun stop() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    /** The receiver's type for a stream address (the default receiver needs it). */
    internal fun contentTypeOf(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".mpd") -> "application/dash+xml"
            else -> "video/mp4"
        }
    }

    /**
     * A live channel as HLS: the default receiver cannot play raw MPEG-TS, and
     * Xtream panels serve the same channel as ".m3u8" next to ".ts".
     */
    internal fun castableUrl(url: String, isLive: Boolean): String {
        if (!isLive) return url
        val query = url.substringAfter('?', "")
        val path = url.substringBefore('?')
        val hls = if (path.endsWith(".ts", ignoreCase = true)) path.dropLast(3) + ".m3u8" else path
        return if (query.isEmpty()) hls else "$hls?$query"
    }
}
