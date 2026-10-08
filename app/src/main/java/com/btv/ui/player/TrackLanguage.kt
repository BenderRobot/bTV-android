package com.btv.ui.player

import java.util.Locale

/**
 * True when a saved track label names the same language as a track whose
 * format language is [trackLanguage] ("fr", "fre", "fra"...). Episodes of
 * one series often label the same track differently ("Français" on one,
 * "FRE" on the next), so a choice must survive by language, not only by label.
 */
fun labelMatchesLanguage(savedLabel: String, trackLanguage: String?): Boolean {
    val iso2 = trackLanguage?.let(::iso2Of) ?: return false
    val locale = Locale(iso2)
    val codes = setOf(iso2) + codesOf(iso2)
    val names = listOf(
        locale.getDisplayLanguage(Locale.FRENCH),
        locale.getDisplayLanguage(Locale.ENGLISH),
        locale.getDisplayLanguage(locale)
    ).map { it.lowercase() }.filter { it.length > 3 }

    val label = savedLabel.lowercase()
    val words = label.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    return words.any { it in codes } || names.any { label.contains(it) }
}

/** "fr", "fre", "fra", "fr-FR" -> "fr"; null for "und", blanks and unknown codes. */
internal fun iso2Of(code: String): String? {
    val c = code.trim().lowercase().substringBefore('-').substringBefore('_')
    if (c.isEmpty() || c == "und") return null
    if (c.length == 2) return c.takeIf { it in ISO2 }
    return THREE_TO_TWO[c]
}

private val ISO2: Set<String> = Locale.getISOLanguages().toSet()

/** ISO 639-2/B codes some streams use instead of the /T ones Java knows. */
private val BIBLIOGRAPHIC = mapOf(
    "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el",
    "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu",
    "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
    "tib" to "bo", "wel" to "cy"
)

private val THREE_TO_TWO: Map<String, String> by lazy {
    ISO2.mapNotNull { iso2 -> runCatching { Locale(iso2).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to iso2 } }
        .toMap() + BIBLIOGRAPHIC
}

private fun codesOf(iso2: String): Set<String> = THREE_TO_TWO.filterValues { it == iso2 }.keys
