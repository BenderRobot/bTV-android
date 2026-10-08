package com.btv.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {

    private fun item(progress: Long, updatedAt: Long, id: String = "VOD|1", deleted: Boolean = false) =
        SyncItem(
            SyncKinds.PROGRESS, id,
            if (deleted) JsonObject(emptyMap()) else JsonObject(mapOf("progressMs" to JsonPrimitive(progress))),
            deleted, updatedAt
        )

    private fun agreedOn(vararg items: SyncItem) =
        SyncState(5, items.associate { it.key to SyncedEntry(it.hash, it.updatedAt, it.deleted) })

    @Test
    fun syncKey_isStableHexAndDependsOnPassword() {
        val a = syncKeyFor("http://srv.example:80/", "user", "pass")
        assertEquals(a, syncKeyFor("http://srv.example:80", "user", "pass"))
        assertTrue(a.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals(a, syncKeyFor("http://srv.example:80", "user", "other"))
        assertFalse(a.contains("pass"))
    }

    @Test
    fun localChanges_firstSyncSendsEverything() {
        val changes = localChanges(listOf(item(10, 100), item(20, 200, "VOD|2")), SyncState(), now = 1000)
        assertEquals(listOf(100L, 200L), changes.map { it.updatedAt })
    }

    @Test
    fun localChanges_unchangedRecordsAreNotResent() {
        val a = item(10, 100)
        assertTrue(localChanges(listOf(a), agreedOn(a), now = 1000).isEmpty())
    }

    @Test
    fun localChanges_changeWithSameTimestampIsRestamped() {
        val agreed = item(10, 100)
        val changes = localChanges(listOf(item(99, 100)), agreedOn(agreed), now = 1000)
        assertEquals(1, changes.size)
        assertEquals(1000L, changes.single().updatedAt)
    }

    @Test
    fun localChanges_removedRecordBecomesTombstone() {
        val agreed = item(10, 100)
        val change = localChanges(emptyList(), agreedOn(agreed), now = 50).single()
        assertTrue(change.deleted)
        assertEquals(agreed.key, change.key)
        // Newer than the agreed version even with a clock behind.
        assertEquals(101L, change.updatedAt)
    }

    @Test
    fun localChanges_knownTombstoneIsNotResent() {
        val tomb = item(0, 300, deleted = true)
        assertTrue(localChanges(emptyList(), agreedOn(tomb), now = 1000).isEmpty())
    }

    @Test
    fun shouldApplyRemote_newRecordIsApplied() {
        assertTrue(shouldApplyRemote(item(10, 100), null, null, null))
    }

    @Test
    fun shouldApplyRemote_unknownDeletionIsIgnored() {
        assertFalse(shouldApplyRemote(item(0, 100, deleted = true), null, null, null))
    }

    @Test
    fun shouldApplyRemote_echoIsNeverApplied() {
        val sent = item(10, 100)
        val agreed = SyncedEntry(sent.hash, sent.updatedAt)
        // Even if the local record changed since (not pushed yet).
        assertFalse(shouldApplyRemote(sent, agreed, localUpdatedAt = 50, localHash = 1))
    }

    @Test
    fun shouldApplyRemote_newestWins() {
        val remote = item(30, 200)
        assertTrue(shouldApplyRemote(remote, null, localUpdatedAt = 100, localHash = 1))
        assertFalse(shouldApplyRemote(remote, null, localUpdatedAt = 300, localHash = 1))
    }

    @Test
    fun shouldApplyRemote_newerDeletionRemovesLocalRecord() {
        val local = item(10, 100)
        assertTrue(shouldApplyRemote(item(0, 200, deleted = true), null, local.updatedAt, local.hash))
    }

    @Test
    fun shouldApplyRemote_identicalContentIsSkipped() {
        val local = item(10, 100)
        assertFalse(shouldApplyRemote(item(10, 500), null, local.updatedAt, local.hash))
    }
}
