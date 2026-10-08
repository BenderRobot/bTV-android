package com.btv.ui.player

import com.btv.data.cache.CatalogCache
import com.btv.data.cache.EpgProgramInfo
import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository
import com.btv.util.decodeEpgText

/** What a live channel airs now, and what comes next - for the OSD and the zap drawer. */
data class LiveProgram(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val nextTitle: String? = null,
    val nextStartMs: Long? = null
) {
    fun progress(now: Long): Float =
        if (endMs > startMs) ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f) else 0f
}

/** The programme airing at [now] in a short guide, with its successor; null when nothing airs. */
fun nowAndNext(listings: List<EpgProgramInfo>, now: Long): LiveProgram? {
    val sorted = listings.sortedBy { it.startTs }
    val index = sorted.indexOfFirst { it.startTs <= now && it.stopTs > now }
    if (index < 0) return null
    val current = sorted[index]
    val next = sorted.getOrNull(index + 1)?.takeIf { it.title.isNotBlank() }
    return LiveProgram(current.title, current.startTs, current.stopTs, next?.title, next?.startTs)
}

/**
 * A channel's short guide through the same session cache as the Live list
 * (CatalogCache, 5 min): the channel just picked there is usually already
 * in it, so the OSD shows its programme at once.
 */
suspend fun loadLiveEpg(authRepository: AuthRepository, session: AuthSession, channelId: String): List<EpgProgramInfo>? =
    CatalogCache.loadEpg(channelId, CatalogCache.generationToken()) {
        authRepository.getShortEpg(session, channelId).map { rawListings ->
            rawListings.map { raw ->
                EpgProgramInfo(
                    title = decodeEpgText(raw.title),
                    startTs = (raw.startTimestamp.toLongOrNull() ?: 0L) * 1000L,
                    stopTs = (raw.stopTimestamp.toLongOrNull() ?: 0L) * 1000L
                )
            }.sortedBy { it.startTs }
        }
    }.getOrNull()?.value
