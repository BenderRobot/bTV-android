package com.btv.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.repository.FavoritesRepository
import com.btv.data.repository.HistoryRepository
import com.btv.data.repository.PlaybackProgressRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dbName = "btv-account-migration-test"

    @get:Rule val helper = MigrationTestHelper(instrumentation, BtvDatabase::class.java)

    @Test fun v4UpgradePreservesRowsAndSeparatesAccountsAndMediaTypes() = runBlocking {
        helper.createDatabase(dbName, 4).apply {
            execSQL("INSERT INTO favorites (id, streamId, type, name, categoryId, categoryName, addedAt) VALUES (1, '42', 'LIVE', 'Live 42', 'live', 'Direct', 10)")
            execSQL("INSERT INTO favorites (id, streamId, type, name, categoryId, categoryName, addedAt) VALUES (2, '42', 'VOD', 'Movie 42', 'vod', 'Films', 11)")
            execSQL("INSERT INTO history (id, streamId, type, name, categoryId, categoryName, viewCount, lastViewedAt) VALUES (1, '42', 'LIVE', 'Live 42', 'live', 'Direct', 2, 10)")
            execSQL("INSERT INTO history (id, streamId, type, name, categoryId, categoryName, viewCount, lastViewedAt) VALUES (2, '42', 'VOD', 'Movie 42', 'vod', 'Films', 1, 11)")
            execSQL("INSERT INTO playback_progress (streamId, type, progressMs, durationMs, progressPercent, isCompleted, lastProgressedAt) VALUES ('42', 'VOD', 20000, 100000, 20, 0, 11)")
            execSQL("INSERT INTO user_session (username, password, serverUrl, userId, userStatus, expirationDate, activeConnections, createdAt, isTrial, lastLoginAt) VALUES ('alice', 'old-secret', 'https://panel.example', 'alice', 'active', 9999999999999, 1, '2026-10-05', 0, 11)")
            close()
        }
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()

        val db = Room.databaseBuilder(instrumentation.targetContext, BtvDatabase::class.java, dbName)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
        try {
            val owner = AccountScope().apply { activate("https://panel.example", "alice") }
            val other = AccountScope().apply { activate("https://panel.example", "bob") }
            val favoritesA = FavoritesRepository(db.favoritesDao(), owner)
            val favoritesB = FavoritesRepository(db.favoritesDao(), other)
            val historyA = HistoryRepository(db.historyDao(), owner)
            val historyB = HistoryRepository(db.historyDao(), other)
            val progressA = PlaybackProgressRepository(db.playbackProgressDao(), owner)
            val progressB = PlaybackProgressRepository(db.playbackProgressDao(), other)

            assertNull(db.userSessionDao().getByUsername("alice")?.password)

            assertTrue(favoritesA.getAllFavorites().first().isEmpty())
            assertTrue(favoritesB.getAllFavorites().first().isEmpty())
            db.claimLegacyRows(owner.requireKey())
            assertEquals(2, favoritesA.getAllFavorites().first().size)
            assertEquals("Live 42", db.favoritesDao().getByStreamId(owner.requireKey(), "LIVE", "42")?.name)
            assertEquals("Movie 42", db.favoritesDao().getByStreamId(owner.requireKey(), "VOD", "42")?.name)
            assertEquals(2, historyA.getAllHistory().first().size)
            assertEquals(20_000L, progressA.getProgressSync("42", "VOD")?.progressMs)
            assertTrue(favoritesB.getAllFavorites().first().isEmpty())
            assertTrue(historyB.getAllHistory().first().isEmpty())
            assertNull(progressB.getProgressSync("42", "VOD"))

            favoritesB.addFavorite("42", "LIVE", "Bob Live", "live", "Direct")
            historyB.addToHistory("42", "VOD", "Bob Movie", "vod", "Films")
            progressB.saveProgress("42", "VOD", 30_000L, 100_000L)
            assertEquals(1, favoritesB.getAllFavorites().first().size)
            assertEquals("Bob Live", db.favoritesDao().getByStreamId(other.requireKey(), "LIVE", "42")?.name)
            assertEquals("Live 42", db.favoritesDao().getByStreamId(owner.requireKey(), "LIVE", "42")?.name)
            assertEquals(20_000L, progressA.getProgressSync("42", "VOD")?.progressMs)
            assertEquals(30_000L, progressB.getProgressSync("42", "VOD")?.progressMs)
        } finally {
            db.close()
        }
    }

    @Test fun v5UpgradeKeepsUserDataAndAddsSnapshotAndTrackTables() = runBlocking {
        val name = "btv-v5-to-v7-migration-test"
        instrumentation.targetContext.deleteDatabase(name)
        val owner = AccountScope().apply { activate("https://panel.example", "alice") }
        val key = owner.requireKey()
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO favorites (accountKey, streamId, type, name, categoryId, categoryName, addedAt) VALUES ('$key', '7', 'SERIES', 'Show 7', 's', 'Séries', 10)")
            execSQL("INSERT INTO playback_progress (accountKey, streamId, type, progressMs, durationMs, progressPercent, isCompleted, lastProgressedAt) VALUES ('$key', '42', 'VOD', 20000, 100000, 20, 0, 11)")
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).close()

        val db = Room.databaseBuilder(instrumentation.targetContext, BtvDatabase::class.java, name)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
        try {
            assertEquals("Show 7", db.favoritesDao().getByStreamId(key, "SERIES", "7")?.name)
            assertEquals(20_000L, PlaybackProgressRepository(db.playbackProgressDao(), owner).getProgressSync("42", "VOD")?.progressMs)

            val tracks = com.btv.data.repository.TrackPreferenceRepository(db.trackPreferenceDao(), owner)
            tracks.save(key, "VOD", "42", "Français", null)
            assertEquals("Français", tracks.get("VOD", "42")?.audioLabel)
            assertNull(tracks.get("VOD", "42")?.subtitleLabel)

            val episodes = com.btv.data.repository.NewEpisodesRepository(db.seriesEpisodeSnapshotDao(), db.favoritesDao(), owner)
            // First check only records the snapshot; the second reports what appeared since.
            assertTrue(episodes.checkFavoriteSeries { listOf("e1", "e2") }.isEmpty())
            val found = episodes.checkFavoriteSeries { listOf("e1", "e2", "e3") }
            assertEquals(1, found.single().count)
            assertEquals(mapOf("7" to 1), episodes.newEpisodeCounts().first())
            episodes.acknowledge("7")
            assertTrue(episodes.newEpisodeCounts().first().isEmpty())
        } finally {
            db.close()
            instrumentation.targetContext.deleteDatabase(name)
        }
    }
}
