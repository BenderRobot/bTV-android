package com.btv.ui.browse

import com.btv.data.model.XtreamChannel
import com.btv.ui.player.defaultLaunchVariant
import com.btv.ui.player.liveChannelKey
import com.btv.ui.player.liveDisplayName
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One Rediffusion sidebar row: every quality of a channel that keeps an
 * archive. [representative] is what OK plays (Full HD first, like Live);
 * the others back it up in the player and may hold the guide it lacks.
 */
data class ReplayChannelGroup(
    val key: String,
    val displayName: String,
    val representative: XtreamChannel,
    val variants: List<XtreamChannel>,
    val categoryName: String?
) {
    val archiveDays: Int get() = variants.maxOf { (it.tvArchiveDuration ?: 1).coerceAtLeast(1) }

    /** The representative first, then the other qualities: where to look for a guide. */
    val guideSources: List<XtreamChannel> get() = listOf(representative) + variants.filter { it !== representative }
}

/**
 * Folds qualities together and orders rows the way a TV guide does: by
 * live category (panel categories are thematic - généralistes, sport,
 * cinéma...), then by name. [categoryNames] maps category ids to names.
 */
fun groupReplayChannels(channels: List<XtreamChannel>, categoryNames: Map<String, String>): List<ReplayChannelGroup> =
    channels
        .groupBy { liveChannelKey(it.name).ifEmpty { it.streamId } }
        .map { (key, variants) ->
            val representative = defaultLaunchVariant(variants) { it.name } ?: variants.first()
            ReplayChannelGroup(
                key = key,
                displayName = liveDisplayName(representative.name),
                representative = representative,
                variants = variants,
                categoryName = representative.categoryId?.let(categoryNames::get) ?: representative.categoryName
            )
        }
        .sortedWith(compareBy<ReplayChannelGroup>({ it.categoryName == null }, { it.categoryName.orEmpty() }, { it.displayName }))

/** "7 j · |FR| GÉNÉRALISTES": the sidebar line under a channel name. */
fun ReplayChannelGroup.sidebarSubtitle(): String =
    listOfNotNull("$archiveDays j", categoryName?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** Day tabs, most recent first: "Aujourd'hui", "Hier", then "lun. 5 oct.". */
fun replayDays(startTimesMs: List<Long>, zone: ZoneId = ZoneId.systemDefault()): List<LocalDate> =
    startTimesMs.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.distinct().sortedDescending()

fun replayDayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Aujourd'hui"
    today.minusDays(1) -> "Hier"
    else -> DAY_LABEL.format(day).replaceFirstChar { it.uppercase(Locale.FRANCE) }
}

fun replayDayOf(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()

private val DAY_LABEL = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRANCE)
private val PANEL_START = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * How far the panel's clock is from UTC, learned from any guide entry:
 * its `start` is panel local time, its timestamp is absolute. Lets a
 * channel without a guide still be addressed by the hour.
 */
fun panelOffsetMs(start: String?, startTimestampSec: String): Long? {
    val local = start?.let { runCatching { LocalDateTime.parse(it.take(19), PANEL_START) }.getOrNull() } ?: return null
    val absolute = startTimestampSec.toLongOrNull() ?: return null
    return local.toEpochSecond(ZoneOffset.UTC) * 1000 - absolute * 1000
}

/** [timeMs] written in the panel's local clock, as timeshift addresses expect it. */
fun panelStartOf(timeMs: Long, offsetMs: Long): String =
    PANEL_START.format(LocalDateTime.ofEpochSecond((timeMs + offsetMs) / 1000, 0, ZoneOffset.UTC))

/** One archive hour of a channel without a guide. */
data class ReplaySlot(val startMs: Long, val endMs: Long, val panelStart: String)

/**
 * Whole hours that have ended, back to the start of the archive window,
 * most recent first. [offsetMs] turns each into the panel's local time.
 */
fun hourlySlots(nowMs: Long, archiveDays: Int, offsetMs: Long): List<ReplaySlot> {
    val hour = 60 * 60 * 1000L
    val lastEnd = nowMs - Math.floorMod(nowMs, hour)
    val windowStart = nowMs - archiveDays.coerceAtLeast(1) * 24 * hour
    val slots = ArrayList<ReplaySlot>()
    var end = lastEnd
    while (end - hour >= windowStart) {
        val start = end - hour
        slots += ReplaySlot(start, end, panelStartOf(start, offsetMs))
        end = start
    }
    return slots
}
