package com.btv.data.repository

import com.btv.data.cache.EpgProgramInfo
import com.btv.data.db.dao.EpgDao
import com.btv.data.db.entities.EpgProgramEntity

/**
 * The live list's "now playing" guide, kept on disk so a channel list shows
 * its programmes as soon as it opens - after a restart, or after the
 * in-memory CatalogCache entry was evicted - while the network refresh runs.
 *
 * Reuses the epg_programs table. Its channelId column holds
 * "<account key>|<stream id>": stream ids are only unique per panel.
 */
class LiveEpgDiskCache(private val dao: EpgDao) {

    suspend fun read(accountKey: String, channelIds: List<String>, now: Long): Map<String, List<EpgProgramInfo>> {
        if (channelIds.isEmpty()) return emptyMap()
        val prefix = "$accountKey|"
        return channelIds.chunked(SQLITE_IN_LIMIT)
            .flatMap { chunk -> dao.getUpcomingForChannels(chunk.map { prefix + it }, now) }
            .groupBy({ it.channelId.removePrefix(prefix) }) { EpgProgramInfo(it.title, it.startTime, it.endTime) }
    }

    suspend fun write(accountKey: String, channelId: String, listings: List<EpgProgramInfo>) {
        val scoped = "$accountKey|$channelId"
        dao.deleteChannel(scoped)
        if (listings.isEmpty()) return
        dao.insertPrograms(listings.map {
            EpgProgramEntity(
                programId = "$scoped|${it.startTs}",
                channelId = scoped,
                title = it.title,
                startTime = it.startTs,
                endTime = it.stopTs
            )
        })
    }

    /** Ended programmes are useless for "now playing": drop them. */
    suspend fun prune(now: Long) = dao.deleteOlderThan(now)

    private companion object {
        const val SQLITE_IN_LIMIT = 500
    }
}
