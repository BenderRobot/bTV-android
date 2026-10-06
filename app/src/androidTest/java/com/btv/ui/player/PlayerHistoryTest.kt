package com.btv.ui.player

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.db.BtvDatabase
import com.btv.data.db.AccountScope
import com.btv.data.repository.HistoryRepository
import com.btv.data.repository.PlaybackProgressRepository
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerHistoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val accountScope = AccountScope().apply { activate("https://test.invalid", "history") }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitUntil(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Le lecteur n'a pas atteint l'etat attendu")
    }

    private fun silentWav(name: String, seconds: Int): File {
        val samples = 8_000 * seconds
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

    @Test fun autoNextEpisodeIsRecordedWhenItActuallyStarts() {
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, BtvDatabase::class.java).build()
        val useCase = GetRecentlyWatchedUseCase(HistoryRepository(database.historyDao(), accountScope))
        val progressUseCase = GetPlaybackProgressUseCase(PlaybackProgressRepository(database.playbackProgressDao(), accountScope))
        val first = silentWav("btv-history-episode-a.wav", 1)
        val second = silentWav("btv-history-episode-b.wav", 30)
        val store = ViewModelStore()
        lateinit var player: ExoPlayer
        lateinit var model: PlayerViewModel
        try {
            onMain {
                player = ExoPlayer.Builder(instrumentation.targetContext).build()
                model = PlayerViewModel(player, getPlaybackProgressUseCase = progressUseCase, getRecentlyWatchedUseCase = useCase)
                store.put("player", model)
                model.loadStreamWithResumeCheck(
                    first.toURI().toString(), contentId = "episode-a", progressType = "SERIES",
                    contentName = "Episode A",
                    zapList = listOf(
                        ZapItem("episode-a", "Episode A", "https://example.invalid/a.jpg", first.toURI().toString()),
                        ZapItem("episode-b", "Episode B", "https://example.invalid/b.jpg", second.toURI().toString())
                    ),
                    seriesId = "show-1", seriesName = "Show", seasonNum = 2,
                    categoryId = "show-1", categoryName = "Show"
                )
            }
            waitUntil {
                var onSecond = false
                onMain { onSecond = model.uiState.value.zapIndex == 1 && player.isPlaying }
                onSecond
            }
            waitUntil {
                runBlocking { database.historyDao().getByStreamId(accountScope.requireKey(), "SERIES", "episode-b") } != null
            }
            val a = runBlocking { database.historyDao().getByStreamId(accountScope.requireKey(), "SERIES", "episode-a") }
            val b = runBlocking { database.historyDao().getByStreamId(accountScope.requireKey(), "SERIES", "episode-b") }
            assertTrue(a != null)
            assertEquals("SERIES", b?.type)
            assertEquals("Episode B", b?.name)
            assertEquals("show-1", b?.seriesId)
            assertEquals(2, b?.seasonNumber)
            assertEquals("https://example.invalid/b.jpg", b?.posterUrl)
            assertEquals("wav", b?.containerExtension)
            assertEquals(1, b?.viewCount)
            assertTrue(runBlocking { useCase.getGroupedHistory().first() }.any { it.streamId == "episode-b" })
            onMain { player.seekTo(20_000L) }
            waitUntil {
                var seeked = false
                onMain { seeked = player.currentPosition >= 19_000L }
                seeked
            }
            onMain { model.stopAndExit() }
            waitUntil { runBlocking { progressUseCase.getProgressSync("episode-b", "SERIES") } != null }
            val resume = runBlocking { progressUseCase.getProgressSync("episode-b", "SERIES") }
            assertEquals("SERIES", resume?.type)
            assertTrue((resume?.progressMs ?: 0L) >= 19_000L)
        } finally {
            onMain { store.clear() }
            database.close()
            first.delete()
            second.delete()
        }
    }

    @Test fun failedStreamDoesNotCreateHistory() {
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, BtvDatabase::class.java).build()
        val useCase = GetRecentlyWatchedUseCase(HistoryRepository(database.historyDao(), accountScope))
        val store = ViewModelStore()
        lateinit var player: ExoPlayer
        lateinit var model: PlayerViewModel
        try {
            onMain {
                player = ExoPlayer.Builder(instrumentation.targetContext).build()
                model = PlayerViewModel(player, getRecentlyWatchedUseCase = useCase)
                store.put("player", model)
                model.loadStreamWithResumeCheck(
                    "file:///btv-missing-history.mp4", contentId = "missing",
                    progressType = "VOD", contentName = "Missing"
                )
            }
            waitUntil {
                var failed = false
                onMain { failed = player.playerError != null }
                failed
            }
            assertNull(runBlocking { database.historyDao().getByStreamId(accountScope.requireKey(), "VOD", "missing") })
            onMain { model.stopAndExit() }
        } finally {
            onMain { store.clear() }
            database.close()
        }
    }
}
