package com.btv.ui.player

/**
 * Providers publish the same channel several times under one name with a
 * quality tag ("|FR| CANAL+ SD", "|FR| CANAL+ FHD", "|FR| CANAL+ UHD (DD+ 5.1)").
 * Those siblings are interchangeable sources for the same programme, so the
 * player uses them as automatic fallbacks when the chosen one drops.
 */
enum class LiveQuality(val rank: Int) { SD(1), UNKNOWN(2), HD(2), FHD(3), UHD(4) }

private val UHD_REGEX = Regex("""(?<![\p{L}\p{N}])(UHD|4K|2160P)(?![\p{L}\p{N}])""")
private val FHD_REGEX = Regex("""(?<![\p{L}\p{N}])(FHD|FULL ?HD|1080[PI])(?![\p{L}\p{N}])""")
private val HD_REGEX = Regex("""(?<![\p{L}\p{N}])(HD|720P)(?![\p{L}\p{N}])""")
private val SD_REGEX = Regex("""(?<![\p{L}\p{N}])(SD|576P|480P)(?![\p{L}\p{N}])""")
private val HDR_REGEX = Regex("""(?<![\p{L}\p{N}])(HDR(10)?\+?|DOLBY ?VISION)(?![\p{L}\p{N}])""")

// Everything that describes the encoding rather than the channel itself.
private val TECH_TOKEN_REGEX = Regex(
    """(?<![\p{L}\p{N}])(UHD|4K|2160P|FHD|FULL ?HD|1080[PI]|HD|720P|SD|576P|480P|HDR(10)?\+?|DOLBY ?VISION|HEVC|H\.?26[45]|[0-9]{2}FPS|HQ|LQ)(?![\p{L}\p{N}])""",
    RegexOption.IGNORE_CASE
)
private val BRACKETED_REGEX = Regex("""\([^)]*\)|\[[^\]]*\]""")
private val SEPARATOR_REGEX = Regex("""[^\p{L}\p{N}+&]+""")

fun liveQualityOf(name: String): LiveQuality {
    val upper = name.uppercase()
    return when {
        UHD_REGEX.containsMatchIn(upper) -> LiveQuality.UHD
        FHD_REGEX.containsMatchIn(upper) -> LiveQuality.FHD
        HD_REGEX.containsMatchIn(upper) -> LiveQuality.HD
        SD_REGEX.containsMatchIn(upper) -> LiveQuality.SD
        else -> LiveQuality.UNKNOWN
    }
}

fun isHdrVariant(name: String): Boolean = HDR_REGEX.containsMatchIn(name.uppercase())

/** "|FR| CANAL+ UHD HDR (DD+ 5.1)" -> "FR CANAL+": the language tag stays, "|BE| CANAL+" is another channel. */
fun liveChannelKey(name: String): String =
    name.uppercase()
        .replace(BRACKETED_REGEX, " ")
        .replace(TECH_TOKEN_REGEX, " ")
        .replace(SEPARATOR_REGEX, " ")
        .trim()

/**
 * The selected channel first, then its same-name siblings in fallback order:
 * the closest lower quality first (lighter, so likelier to hold on a weak
 * link), then higher ones, HDR last (washed-out colours on an SDR TV).
 */
fun buildLiveFallbackChain(selected: ZapItem, candidates: List<ZapItem>): List<ZapItem> {
    val key = liveChannelKey(selected.name)
    if (key.isEmpty()) return listOf(selected)
    val selectedRank = liveQualityOf(selected.name).rank
    val siblings = candidates
        .asSequence()
        .filter { it.id != selected.id && it.streamUrl != null && liveChannelKey(it.name) == key }
        .distinctBy { it.id }
        .toList()
    val ordered = siblings.withIndex().sortedWith(
        compareBy<IndexedValue<ZapItem>> { isHdrVariant(it.value.name) }
            .thenBy { liveQualityOf(it.value.name).rank > selectedRank }
            .thenBy { (liveQualityOf(it.value.name).rank - selectedRank).let { d -> if (d > 0) d else -d } }
            .thenBy { it.index }
    ).map { it.value }
    return listOf(selected) + ordered
}

/** Xtream live TS URL (/live/user/pass/id.ts) - an endless byte stream, unlike HLS or VOD. */
fun isXtreamLiveTsPath(path: String?): Boolean {
    val segments = path?.split('/')?.filter { it.isNotEmpty() } ?: return false
    return segments.size >= 4 && segments[0].equals("live", ignoreCase = true) &&
        !segments.last().endsWith(".m3u8", ignoreCase = true)
}

/** "|FR| CANAL+ UHD HDR (DD+ 5.1)" -> "|FR| CANAL+": the name a grouped row shows. */
fun liveDisplayName(name: String): String =
    name.replace(BRACKETED_REGEX, " ")
        .replace(TECH_TOKEN_REGEX, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimEnd('-', '|', ':', '.')
        .trim()
        .ifEmpty { name }

/** Short, human quality label: "FHD", "UHD HDR", "UHD · DD+ 5.1"... */
fun liveQualityLabel(name: String): String {
    val base = when (liveQualityOf(name)) {
        LiveQuality.UHD -> "UHD"
        LiveQuality.FHD -> "FHD"
        LiveQuality.HD -> "HD"
        LiveQuality.SD -> "SD"
        LiveQuality.UNKNOWN -> "Standard"
    }
    val hdr = if (isHdrVariant(name)) " HDR" else ""
    val extra = BRACKETED_REGEX.findAll(name)
        .map { it.value.trim('(', ')', '[', ']', ' ') }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return base + hdr + if (extra.isNotEmpty()) " · $extra" else ""
}

/** Labels for a set of siblings, made unique when two share the same quality tag. */
fun <T> liveQualityLabels(variants: List<T>, name: (T) -> String): List<String> {
    val labels = variants.map { liveQualityLabel(name(it)) }
    val counts = labels.groupingBy { it }.eachCount()
    val seen = HashMap<String, Int>()
    return labels.map { label ->
        if ((counts[label] ?: 0) < 2) label
        else "$label ${seen.merge(label, 1, Int::plus)}"
    }
}

/** Best picture first; at equal quality the SDR version before HDR. */
fun <T> sortByQualityDescending(variants: List<T>, name: (T) -> String): List<T> =
    variants.sortedWith(
        compareByDescending<T> { liveQualityOf(name(it)).rank }
            .thenBy { isHdrVariant(name(it)) }
    )

/**
 * What OK plays when nothing was chosen yet: Full HD, the sweet spot for a
 * film on an IPTV line; UHD is heavy and HDR looks washed out on SDR TVs.
 */
fun <T> defaultLaunchVariant(variants: List<T>, name: (T) -> String): T? {
    val preference = listOf(LiveQuality.FHD, LiveQuality.HD, LiveQuality.UNKNOWN, LiveQuality.UHD, LiveQuality.SD)
    return variants.minByOrNull { item ->
        val quality = preference.indexOf(liveQualityOf(name(item)))
        quality + if (isHdrVariant(name(item))) 10 else 0
    }
}

/** Xtream live URLs only differ by their last segment ("<id>.ts"). */
fun liveUrlForStreamId(url: String, streamId: String): String? {
    val path = url.substringBefore('?')
    val slash = path.lastIndexOf('/')
    if (slash < 0) return null
    val last = path.substring(slash + 1)
    val extension = last.substringAfterLast('.', "")
    if (extension.isEmpty()) return null
    return url.substring(0, slash + 1) + "$streamId.$extension" + url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
}
