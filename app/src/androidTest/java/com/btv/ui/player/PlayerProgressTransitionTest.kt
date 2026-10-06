package com.btv.ui.player

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.db.BtvDatabase
import com.btv.data.db.AccountScope
import com.btv.data.repository.PlaybackProgressRepository
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerProgressTransitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val accountScope = AccountScope().apply { activate("https://test.invalid", "progress") }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitUntil(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Le lecteur n'a pas atteint l'etat attendu")
    }

    private fun silentWav(name: String): File {
        val samples = 8_000 * 30
        val bytes = ByteArray(44 + samples * 2)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(bytes.size - 8)
        header.put("WAVEfmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(8_000)
        header.putInt(16_000)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(samples * 2)
        return File(instrumentation.targetContext.cacheDir, name).apply { writeBytes(bytes) }
    }

    @Test fun savesOutgoingPositionBeforeStartingNextMedia() {
        val database = Room.inMemoryDatabaseBuilder(
            instrumentation.targetContext, BtvDatabase::class.java
        ).build()
        val useCase = GetPlaybackProgressUseCase(PlaybackProgressRepository(database.playbackProgressDao(), accountScope))
        val first = silentWav("btv-transition-a.wav")
        val second = silentWav("btv-transition-b.wav")
        val store = ViewModelStore()
        lateinit var player: ExoPlayer
        lateinit var model: PlayerViewModel
        try {
            onMain {
                player = ExoPlayer.Builder(instrumentation.targetContext).build()
                model = PlayerViewModel(player, useCase)
                store.put("player", model)
                model.loadStreamWithResumeCheck(first.toURI().toString(), contentId = "transition-a", progressType = "VOD", contentName = "A")
            }
            waitUntil {
                var ready = false
                onMain { ready = player.playbackState == Player.STATE_READY && player.duration > 0L }
                ready
            }
            onMain {
                player.seekTo(20_000L)
                model.loadStreamWithResumeCheck(second.toURI().toString(), contentId = "transition-b", progressType = "VOD", contentName = "B")
            }
            waitUntil {
                var startedB = false
                onMain {
                    startedB = player.currentMediaItem?.localConfiguration?.uri.toString() == second.toURI().toString() &&
                        player.playbackState == Player.STATE_READY
                }
                startedB
            }
            val savedA = runBlocking { useCase.getProgressSync("transition-a", "VOD") }
            val savedB = runBlocking { useCase.getProgressSync("transition-b", "VOD") }
            assertTrue(savedA != null)
            assertEquals("VOD", savedA?.type)
            assertTrue((savedA?.progressMs ?: 0L) >= 19_000L)
            assertNull(savedB)
            onMain { assertTrue(player.currentPosition < 10_000L) }
        } finally {
            onMain { store.clear() }
            database.close()
            first.delete()
            second.delete()
        }
    }

    @Test fun resumePromptKeepsIncomingMediaStoppedAndPreservesItsProgress() {
        val database = Room.inMemoryDatabaseBuilder(
            instrumentation.targetContext, BtvDatabase::class.java
        ).build()
        val useCase = GetPlaybackProgressUseCase(PlaybackProgressRepository(database.playbackProgressDao(), accountScope))
        runBlocking { useCase.saveProgress("resume-b", "VOD", 18_000L, 30_000L) }
        val first = silentWav("btv-resume-a.wav")
        val second = silentWav("btv-resume-b.wav")
        val store = ViewModelStore()
        lateinit var player: ExoPlayer
        lateinit var model: PlayerViewModel
        try {
            onMain {
                player = ExoPlayer.Builder(instrumentation.targetContext).build()
                model = PlayerViewModel(player, useCase)
                store.put("player", model)
                model.loadStreamWithResumeCheck(first.toURI().toString(), contentId = "resume-a", progressType = "VOD", contentName = "A")
            }
            waitUntil {
                var ready = false
                onMain { ready = player.playbackState == Player.STATE_READY && player.duration > 0L }
                ready
            }
            onMain {
                player.seekTo(20_000L)
                model.loadStreamWithResumeCheck(second.toURI().toString(), contentId = "resume-b", progressType = "VOD", contentName = "B")
            }
            waitUntil {
                var prompted = false
                onMain { prompted = model.resumePrompt.value != null }
                prompted
            }
            onMain {
                assertEquals(18_000L, model.resumePrompt.value?.resumePositionMs)
                assertEquals(0, player.mediaItemCount)
            }
            assertTrue((runBlocking { useCase.getProgressSync("resume-a", "VOD") }?.progressMs ?: 0L) >= 19_000L)
            assertEquals(18_000L, runBlocking { useCase.getProgressSync("resume-b", "VOD") }?.progressMs)
            onMain { model.confirmResume(false) }
            waitUntil {
                var started = false
                onMain { started = player.playbackState == Player.STATE_READY }
                started
            }
            onMain { assertTrue(player.currentPosition < 10_000L) }
        } finally {
            onMain { store.clear() }
            database.close()
            first.delete()
            second.delete()
        }
    }
}
