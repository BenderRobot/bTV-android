package com.btv.ui.browse

import com.btv.data.store.LiveQualityChoice
import com.btv.ui.player.defaultLaunchVariant
import com.btv.ui.player.liveChannelKey
import com.btv.ui.player.liveDisplayName
import com.btv.ui.player.liveQualityLabels
import com.btv.ui.player.sortByQualityDescending

/**
 * One row of the Live list: every quality the provider publishes for a
 * channel ("|FR| CANAL+ SD/HD/FHD/UHD") folded into a single entry.
 */
data class LiveChannelGroup(
    val key: String,
    /** What the list shows: the family name and the best EPG line any sibling has. */
    val row: ContentItem,
    /** Identity for focus, EPG and favourite: the favourited sibling, else [launchVariant]. */
    val representative: ContentItem,
    /** What OK plays: the remembered quality, else the default pick. */
    val launchVariant: ContentItem,
    /** Best quality first, aligned with [qualityLabels]. */
    val variants: List<ContentItem>,
    val qualityLabels: List<String>
) {
    val hasQualities: Boolean get() = variants.size > 1

    fun contains(id: String?): Boolean = id != null && variants.any { it.id == id }
}

fun groupLiveChannels(
    items: List<ContentItem>,
    choices: Map<String, LiveQualityChoice>,
    favoriteIds: Set<String>
): List<LiveChannelGroup> {
    val byKey = LinkedHashMap<String, MutableList<ContentItem>>()
    for (item in items) {
        // A name made only of tags can't be matched safely: keep it alone.
        val key = liveChannelKey(item.name).ifEmpty { "#${item.id}" }
        byKey.getOrPut(key) { mutableListOf() } += item
    }
    return byKey.map { (key, members) ->
        val variants = sortByQualityDescending(members.distinctBy { it.id }) { it.name }
        if (variants.size == 1) {
            val only = variants.first()
            return@map LiveChannelGroup(key, only, only, only, variants, listOf(""))
        }
        val launch = variants.firstOrNull { it.id == choices[key]?.streamId }
            ?: defaultLaunchVariant(variants) { it.name }
            ?: variants.first()
        val representative = variants.firstOrNull { it.id in favoriteIds } ?: launch
        // Providers often fill the guide on one quality only (SD here, not HD).
        val epgSource = representative.takeIf { it.badge != null }
            ?: variants.firstOrNull { it.badge != null }
            ?: representative
        val row = representative.copy(
            name = liveDisplayName(representative.name),
            badge = epgSource.badge,
            epgProgress = epgSource.epgProgress,
            epgStartTime = epgSource.epgStartTime,
            epgEndTime = epgSource.epgEndTime
        )
        LiveChannelGroup(key, row, representative, launch, variants, liveQualityLabels(variants) { it.name })
    }
}
