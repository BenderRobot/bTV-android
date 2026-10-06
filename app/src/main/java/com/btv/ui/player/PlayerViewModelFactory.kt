package com.btv.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.session.MediaSession
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase

/**
 * Activity-scoped: obtained with the Activity as ViewModelStoreOwner from
 * both the fullscreen "player" route and the mini-player overlay, so both
 * resolve to the SAME PlayerViewModel/ExoPlayer instance - required for the
 * mini-player to actually keep playing under Browse instead of being torn
 * down on navigation, like Tizen's reused <video>/AVPlay element.
 */
class PlayerViewModelFactory(
    private val context: Context,
    private val getPlaybackProgressUseCase: GetPlaybackProgressUseCase?,
    private val getRecentlyWatchedUseCase: GetRecentlyWatchedUseCase? = null,
    private val fetchSeriesEpisodes: (suspend (seriesId: String) -> SeriesEpisodesBySeason?)? = null,
    private val trackPreferenceRepository: com.btv.data.repository.TrackPreferenceRepository? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlayerViewModel::class.java))
        val appContext = context.applicationContext
        val player = buildBtvExoPlayer(appContext).apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
        }
        val session = try {
            MediaSession.Builder(appContext, player).build()
        } catch (error: Exception) {
            player.release()
            throw error
        }
        return PlayerViewModel(
            player, getPlaybackProgressUseCase, fetchSeriesEpisodes, getRecentlyWatchedUseCase, session,
            trackPreferenceRepository
        ) as T
    }
}
