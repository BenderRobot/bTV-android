package com.btv.data.repository

import com.btv.data.db.dao.ReplayDao
import com.btv.data.db.entities.ReplayChannelEntity
import com.btv.data.db.entities.ReplayProgramEntity
import com.btv.data.model.XtreamChannel
import com.btv.data.model.XtreamEpgListing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayArchiveStoreTest {
    private class FakeReplayDao : ReplayDao {
        val channels = mutableListOf<ReplayChannelEntity>()
        val programs = mutableListOf<ReplayProgramEntity>()
        override suspend fun getChannels(accountKey: String) = channels.filter { it.accountKey == accountKey }
        override suspend fun deleteChannels(accountKey: String) { channels.removeAll { it.accountKey == accountKey } }
        override suspend fun insertChannels(channels: List<ReplayChannelEntity>) { this.channels += channels }
        override suspend fun getPrograms(accountKey: String, streamId: String) =
            programs.filter { it.accountKey == accountKey && it.streamId == streamId }.sortedBy { it.startTs }
        override suspend fun deletePrograms(accountKey: String, streamId: String) {
            programs.removeAll { it.accountKey == accountKey && it.streamId == streamId }
        }
        override suspend fun insertPrograms(programs: List<ReplayProgramEntity>) { this.programs += programs }
        override suspend fun deleteProgramsEndedBefore(cutoffSeconds: Long) { programs.removeAll { it.stopTs < cutoffSeconds } }
    }

    private val dao = FakeReplayDao()
    private val store = ReplayArchiveStore(dao)

    @Test
    fun `channels round-trip per account, icons carrying the login are dropped`() = runBlocking {
        val channels = listOf(
            XtreamChannel(streamId = "1", name = "|FR| TF1 HD", streamIcon = "http://img/tf1.png", categoryId = "10", tvArchive = 1, tvArchiveDuration = 7),
            XtreamChannel(streamId = "2", name = "|FR| M6 HD", streamIcon = "http://panel/images/user/s%40cret/m6.png", tvArchive = 1)
        )
        store.writeChannels("A", channels, nowMs = 5_000L, credentials = listOf("user", "s@cret"))

        val read = store.readChannels("A")!!
        assertEquals(5_000L, read.fetchedAtMs)
        assertEquals(listOf("1", "2"), read.value.map { it.streamId })
        assertEquals("http://img/tf1.png", read.value[0].streamIcon)
        assertEquals(7, read.value[0].tvArchiveDuration)
        assertEquals(1, read.value[0].tvArchive)
        assertNull(read.value[1].streamIcon)
        assertNull(store.readChannels("B"))

        store.writeChannels("A", channels.take(1), nowMs = 6_000L, credentials = emptyList())
        assertEquals(listOf("1"), store.readChannels("A")!!.value.map { it.streamId })
    }

    @Test
    fun `programs keep what the timeshift url needs and are replaced as a whole`() = runBlocking {
        val listing = XtreamEpgListing(
            title = "VGl0cmU=", description = "RGVzYw==", start = "2026-10-07 20:00:00", end = "2026-10-07 21:00:00",
            startTimestamp = "1000", stopTimestamp = "4600"
        )
        store.writePrograms("A", "1", listOf(listing, listing.copy(startTimestamp = "bad")), nowMs = 9_000L)
        val read = store.readPrograms("A", "1")!!
        assertEquals(listOf(listing), read.value)
        assertEquals(9_000L, read.fetchedAtMs)

        store.writePrograms("A", "1", emptyList(), nowMs = 10_000L)
        assertNull(store.readPrograms("A", "1"))
    }

    @Test
    fun `prune drops programs past any archive window`() = runBlocking {
        val day = 24L * 60 * 60
        val now = 100L * day
        store.writePrograms("A", "1", listOf(
            XtreamEpgListing(startTimestamp = "${now - 20 * day}", stopTimestamp = "${now - 20 * day + 3600}"),
            XtreamEpgListing(startTimestamp = "${now - 2 * day}", stopTimestamp = "${now - 2 * day + 3600}")
        ), nowMs = now * 1000)
        store.prune(now * 1000)
        assertEquals(listOf("${now - 2 * day}"), store.readPrograms("A", "1")!!.value.map { it.startTimestamp })
    }

    @Test
    fun `trim keeps the archive window and the program on air, not the future guide`() {
        val hour = 3600L
        val now = 1_000_000L * hour
        fun program(startH: Long, stopH: Long) =
            XtreamEpgListing(startTimestamp = "${now + startH * hour}", stopTimestamp = "${now + stopH * hour}")
        val tooOld = program(-30, -29)
        val archived = program(-5, -4)
        val onAir = program(-1, 1)
        val future = program(2, 3)
        val kept = trimToArchiveWindow(listOf(tooOld, archived, onAir, future), nowMs = now * 1000, archiveDays = 1)
        assertEquals(listOf(archived, onAir), kept)
    }
}
