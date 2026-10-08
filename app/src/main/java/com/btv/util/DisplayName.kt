package com.btv.util

/** A provider title made presentable: the name, and its tags shown apart as chips. */
data class DisplayName(val title: String, val tags: List<String>)

// "|FR| ", "[FR] ", "|EN-US| " / "FR: ", "FR| " at the very start.
private val BRACKET_PREFIX = Regex("""^\s*[|\[]\s*([A-Z0-9]{2,4}(?:[ -][A-Z0-9]{2,4})?)\s*[|\]]\s*""")
// "FR: " or "FR| " - the bar glued to the code, so "USA | TV" stays a category name.
private val COLON_PREFIX = Regex("""^\s*([A-Z]{2,3})(?:\s*:|\|)\s+""")
// "(VOST)", "[MULTI]" at the end.
private val TRAILING_GROUP = Regex("""\s*[(\[]\s*([^()\[\]]{1,14}?)\s*[)\]]\s*$""")
// " FHD", " 4K" at the end (channel names).
private val TRAILING_WORD = Regex("""\s+([A-Z0-9+]{2,6})\s*$""")

private val RELEASE_TAGS = setOf(
    "VOST", "VOSTFR", "VOSTA", "MULTI", "MULTI-SUB", "MULTISUB", "VF", "VFF", "VFQ", "VFI", "VO", "SUB", "SUBS",
    "TRUEFRENCH", "FRENCH"
)
private val QUALITY_TAGS = setOf("4K", "UHD", "FHD", "HD", "SD", "HDR", "HEVC", "H265", "3D", "HD+", "FHD+", "RAW")

/**
 * "|FR| \"Ippon\" Again! (VOST)" -> "\"Ippon\" Again!" + [FR, VOST];
 * "|US| CBS 2 NEW YORK (WCBS)" keeps "(WCBS)", which is not a tag. Display
 * only: search, sorting and storage keep the provider's raw names.
 */
fun displayName(raw: String): DisplayName {
    var title = raw.trim()
    val tags = ArrayList<String>()
    var hasLanguage = false

    (BRACKET_PREFIX.find(title) ?: COLON_PREFIX.find(title))?.let { match ->
        val rest = title.substring(match.range.last + 1).trim()
        if (rest.isNotEmpty()) {
            tags += match.groupValues[1].uppercase()
            hasLanguage = true
            title = rest
        }
    }

    // Peel tags off the end, last first, each inserted after the language: "Film (MULTI) (4K)".
    while (true) {
        val group = TRAILING_GROUP.find(title)
        val groupTag = group?.groupValues?.get(1)?.uppercase()?.replace(" ", "")
        if (group != null && groupTag != null && (groupTag in RELEASE_TAGS || groupTag in QUALITY_TAGS)) {
            val rest = title.substring(0, group.range.first).trim()
            if (rest.isEmpty()) break
            tags.add(if (hasLanguage) 1 else 0, groupTag)
            title = rest
            continue
        }
        val word = TRAILING_WORD.find(title)
        val wordTag = word?.groupValues?.get(1)
        if (word != null && wordTag != null && wordTag in QUALITY_TAGS) {
            val rest = title.substring(0, word.range.first).trim()
            if (rest.isEmpty()) break
            tags.add(if (hasLanguage) 1 else 0, wordTag)
            title = rest
            continue
        }
        break
    }
    return DisplayName(title, tags.distinct())
}

/** The "|FR|" / "[IT]" / "US:" language tag of a provider title, if any. */
fun displayLanguage(raw: String): String? {
    val match = BRACKET_PREFIX.find(raw) ?: COLON_PREFIX.find(raw) ?: return null
    if (raw.substring(match.range.last + 1).isBlank()) return null
    return match.groupValues[1].uppercase()
}

/** Just the clean title. */
fun displayTitle(raw: String): String = displayName(raw).title

/** "|FR| ACTION | AVENTURE" -> "ACTION · AVENTURE". */
fun displayCategory(raw: String): String {
    val title = (BRACKET_PREFIX.find(raw) ?: COLON_PREFIX.find(raw))?.let { match ->
        raw.substring(match.range.last + 1).trim().ifEmpty { null }
    } ?: raw.trim()
    return title.replace(Regex("""\s+\|\s+"""), " · ")
}

/**
 * Provider durations made readable: "02:36:21" -> "2 h 36", "00:45:09" ->
 * "45 min", "5400" (seconds) or "95 min" kept/converted the same way.
 * Anything not understood is returned as is.
 */
fun displayDuration(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val totalMinutes: Int = when {
        Regex("""^\d{1,2}:\d{2}:\d{2}$""").matches(text) -> {
            val (h, m, s) = text.split(':').map { it.toInt() }
            h * 60 + m + if (s >= 30) 1 else 0
        }
        Regex("""^\d{1,3}:\d{2}$""").matches(text) -> text.substringBefore(':').toInt()
        Regex("""^\d+\s*min\.?$""", RegexOption.IGNORE_CASE).matches(text) -> text.filter { it.isDigit() }.toInt()
        else -> return text
    }
    if (totalMinutes <= 0) return null
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "$minutes min"
        minutes == 0 -> "$hours h"
        else -> "$hours h ${minutes.toString().padStart(2, '0')}"
    }
}
