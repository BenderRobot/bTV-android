package com.btv.ui.browse

import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.model.AuthSession
import com.btv.data.model.UserInfo
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowAllSnapshotStoreTest {
    @Test fun snapshotSurvivesNewStoreAndKeepsCredentialsOutOfFile() = runBlocking {
        val directory = testDirectory()
        try {
            var now = 1_000_000L
            val session = AuthSession("https://example.test", "alice", "secret", UserInfo(auth = 1))
            val store = ShowAllSnapshotStore(directory) { now }
            val refs = (1..302).map { ShowAllRef("cat", it.toString(), "Film $it") }
            val cards = refs.take(300).map {
                ContentItem(
                    id = it.id, name = it.name,
                    posterUrl = "https://example.test/art/%73ecret/${it.id}.jpg",
                    streamUrl = "https://example.test/movie/alice/secret/${it.id}.mkv"
                )
            }
            store.write(session, ContentType.VOD, emptySet(), refs, cards)

            val restored = ShowAllSnapshotStore(directory) { now }
                .read(session, ContentType.VOD, emptySet())!!
            assertEquals(302, restored.refs.size)
            assertEquals("Film 302", restored.refs.last().name)
            assertEquals(300, restored.preview.size)
            assertEquals("mkv", restored.preview.first().extension)
            assertNull(restored.preview.first().posterUrl)
            val diskText = GZIPInputStream(directory.listFiles()!!.single().inputStream())
                .bufferedReader().use { it.readText() }
            assertFalse(diskText.contains("secret"))
            assertFalse(diskText.contains("/movie/alice/"))

            assertNull(restoredFor(directory, AuthSession("https://example.test", "bob", "secret", UserInfo(auth = 1)), now))
            now += 60 * 60 * 1_000L + 1
            assertNull(restoredFor(directory, session, now))
            now -= 60 * 60 * 1_000L + 1
            assertTrue(restoredFor(directory, session, now) != null)
            store.invalidate(session)
            assertNull(restoredFor(directory, session, now))
        } finally {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }
    }

    @Test fun corruptedSnapshotIsIgnoredAndDeleted() = runBlocking {
        val directory = testDirectory()
        try {
            val session = AuthSession("https://example.test", "alice", "secret", UserInfo(auth = 1))
            val store = ShowAllSnapshotStore(directory) { 1_000_000L }
            store.write(session, ContentType.LIVE, setOf("AR"), listOf(ShowAllRef("1", "7", "News")),
                listOf(ContentItem("7", "News")))
            assertNull(store.read(session, ContentType.LIVE, setOf("FR")))
            directory.listFiles()!!.single().writeText("not-gzip")
            assertNull(store.read(session, ContentType.LIVE, setOf("AR")))
            assertTrue(directory.listFiles().isNullOrEmpty())
        } finally {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }
    }

    private suspend fun restoredFor(directory: File, session: AuthSession, now: Long) =
        ShowAllSnapshotStore(directory) { now }.read(session, ContentType.VOD, emptySet())

    private fun testDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "show-all-snapshot-test-${System.nanoTime()}").apply { mkdirs() }
    }
}
