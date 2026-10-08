package com.btv.data.repository

import com.btv.data.db.dao.ReplayDao
import com.btv.data.db.entities.ReplayChannelEntity
import com.btv.data.db.entities.ReplayProgramEntity
import com.btv.data.model.XtreamChannel
import com.btv.data.model.XtreamEpgListing

/**
 * Rediffusion on disk, per account: the archivable channels and each
 * channel's archived programs. Browse shows these at once and refreshes
 * them from the panel behind (stale-while-revalidate), so a restart no
 * longer means waiting on get_live_streams and get_simple_data_table.
 */
class ReplayArchiveStore(
    private val dao: ReplayDao,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    data class Snapshot<T>(val value: T, val fetchedAtMs: Long)

    suspend fun readChannels(accountKey: String): Snapshot<List<XtreamChannel>>? {
        val rows = dao.getChannels(accountKey).ifEmpty { return null }
        return Snapshot(rows.map { it.toChannel() }, rows.minOf { it.fetchedAt })
    }

    /** [credentials] keep icon URLs that embed the account's login out of the database. */
    suspend fun writeChannels(accountKey: String, channels: List<XtreamChannel>, nowMs: Long, credentials: List<String>) {
        val rows = channels.map { channel ->
            ReplayChannelEntity(
                accountKey = accountKey,
                streamId = channel.streamId,
                name = channel.name,
                streamIcon = channel.streamIcon?.takeUnless { icon -> containsAny(icon, credentials) },
                categoryId = channel.categoryId,
                categoryName = channel.categoryName,
                archiveDays = channel.tvArchiveDuration,
                fetchedAt = nowMs
            )
        }
        transaction {
            dao.deleteChannels(accountKey)
            if (rows.isNotEmpty()) dao.insertChannels(rows)
        }
    }

    suspend fun readPrograms(accountKey: String, streamId: String): Snapshot<List<XtreamEpgListing>>? {
        val rows = dao.getPrograms(accountKey, streamId).ifEmpty { return null }
        return Snapshot(rows.map { it.toListing() }, rows.minOf { it.fetchedAt })
    }

    suspend fun writePrograms(accountKey: String, streamId: String, listings: List<XtreamEpgListing>, nowMs: Long) {
        val rows = listings.mapNotNull { listing ->
            ReplayProgramEntity(
                accountKey = accountKey,
                streamId = streamId,
                startTs = listing.startTimestamp.toLongOrNull() ?: return@mapNotNull null,
                stopTs = listing.stopTimestamp.toLongOrNull() ?: return@mapNotNull null,
                start = listing.start,
                end = listing.end,
                title = listing.title,
                description = listing.description,
                fetchedAt = nowMs
            )
        }.distinctBy { it.startTs }
        transaction {
            dao.deletePrograms(accountKey, streamId)
            if (rows.isNotEmpty()) dao.insertPrograms(rows)
        }
    }

    /** No panel keeps an archive this long: older programs can't be played any more. */
    suspend fun prune(nowMs: Long) = dao.deleteProgramsEndedBefore(nowMs / 1000 - MAX_ARCHIVE_SECONDS)

    private fun containsAny(value: String, credentials: List<String>): Boolean =
        credentials.any { secret ->
            secret.isNotEmpty() && (value.contains(secret) || value.contains(java.net.URLEncoder.encode(secret, "UTF-8")))
        }

    private fun ReplayChannelEntity.toChannel() = XtreamChannel(
        streamId = streamId,
        name = name,
        streamIcon = streamIcon,
        categoryId = categoryId,
        categoryName = categoryName,
        tvArchive = 1,
        tvArchiveDuration = archiveDays
    )

    private fun ReplayProgramEntity.toListing() = XtreamEpgListing(
        title = title,
        description = description,
        start = start,
        end = end,
        startTimestamp = startTs.toString(),
        stopTimestamp = stopTs.toString()
    )

    companion object {
        private const val MAX_ARCHIVE_SECONDS = 15L * 24 * 60 * 60
    }
}

/**
 * Only what Rediffusion can use: programs that started within the
 * channel's archive window and have started by now (the one on air stays,
 * for restarting it from its beginning). get_simple_data_table often
 * returns days of future guide on top, which would sit in memory and on
 * disk for nothing.
 */
fun trimToArchiveWindow(listings: List<XtreamEpgListing>, nowMs: Long, archiveDays: Int?): List<XtreamEpgListing> {
    val windowStartSec = (nowMs - (archiveDays ?: 1).coerceAtLeast(1) * 24L * 60 * 60 * 1000) / 1000
    val nowSec = nowMs / 1000
    return listings.filter { listing ->
        val start = listing.startTimestamp.toLongOrNull() ?: return@filter false
        val stop = listing.stopTimestamp.toLongOrNull() ?: return@filter false
        stop > start && start >= windowStartSec && start <= nowSec
    }
}
