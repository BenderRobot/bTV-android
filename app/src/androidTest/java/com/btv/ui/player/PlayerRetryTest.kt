package com.btv.ui.player

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlayerRetryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun awaitError(player: ExoPlayer) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var failed = false
            onMain { failed = player.playerError != null }
            if (failed) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Le flux local invalide n'a pas produit d'erreur")
    }

    @Test fun retryFromPreviousMediaDoesNotReplaceNewMedia() {
        lateinit var player: ExoPlayer
        lateinit var model: PlayerViewModel
        val store = ViewModelStore()
        val oldUri = "file:///btv-missing-retry-a.mp4"
        val newUri = "file:///btv-missing-retry-b.mp4"
        onMain {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            model = PlayerViewModel(player)
            store.put("player", model)
            model.loadStreamWithResumeCheck(oldUri, contentId = "a", progressType = "VOD", contentName = "A")
        }
        try {
            awaitError(player)
            onMain {
                model.loadStreamWithResumeCheck(newUri, contentId = "b", progressType = "VOD", contentName = "B")
            }
            SystemClock.sleep(2_500L)
            onMain {
                assertEquals(newUri, player.currentMediaItem?.localConfiguration?.uri.toString())
                assertEquals("B", model.uiState.value.contentName)
            }
        } finally {
            onMain { store.clear() }
        }
    }

    @Test fun exitCancelsScheduledRetry() {
        lateinit var player: ExoPlayer
        lateinit var model: PlayerViewModel
        val store = ViewModelStore()
        onMain {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            model = PlayerViewModel(player)
            store.put("player", model)
            model.loadStreamWithResumeCheck(
                "file:///btv-missing-retry-exit.mp4", contentId = "a",
                progressType = "VOD", contentName = "A"
            )
        }
        try {
            awaitError(player)
            onMain { model.stopAndExit() }
            SystemClock.sleep(2_500L)
            onMain {
                assertTrue(player.mediaItemCount == 0)
                assertEquals("", model.uiState.value.streamUrl)
            }
        } finally {
            onMain { store.clear() }
        }
    }

    @Test fun backgroundCancelsRetryAndOffersManualResume() {
        lateinit var player: ExoPlayer
        lateinit var model: PlayerViewModel
        val store = ViewModelStore()
        onMain {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            model = PlayerViewModel(player)
            store.put("player", model)
            model.loadStreamWithResumeCheck(
                "file:///btv-missing-retry-background.mp4", contentId = "a",
                progressType = "VOD", contentName = "A"
            )
        }
        try {
            awaitError(player)
            onMain { model.onAppBackgrounded() }
            SystemClock.sleep(2_500L)
            onMain {
                assertEquals(0, model.uiState.value.retryCount)
                assertTrue(model.uiState.value.errorMessage?.contains("Réessayez") == true)
                model.onAppForegrounded()
                model.retryCurrentStream()
                assertEquals(null, model.uiState.value.errorMessage)
            }
        } finally {
            onMain { store.clear() }
        }
    }

    @Test fun changingMediaCancelsInFlightSeasonFetch() {
        lateinit var player: ExoPlayer
        lateinit var model: PlayerViewModel
        val store = ViewModelStore()
        val fetchStarted = CountDownLatch(1)
        val fetchCancelled = CountDownLatch(1)
        onMain {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            model = PlayerViewModel(player, fetchSeriesEpisodes = {
                suspendCancellableCoroutine { continuation ->
                    fetchStarted.countDown()
                    continuation.invokeOnCancellation { fetchCancelled.countDown() }
                }
            })
            store.put("player", model)
            model.loadStreamWithResumeCheck(
                "file:///btv-season-a.mp4", contentId = "episode-a",
                progressType = "SERIES", contentName = "Episode A",
                seriesId = "series-a", seasonNum = 1
            )
        }
        try {
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
            onMain {
                model.loadStreamWithResumeCheck(
                    "file:///btv-season-b.mp4", contentId = "movie-b",
                    progressType = "VOD", contentName = "Film B"
                )
            }
            assertTrue(fetchCancelled.await(5, TimeUnit.SECONDS))
        } finally {
            onMain { store.clear() }
        }
    }
}
