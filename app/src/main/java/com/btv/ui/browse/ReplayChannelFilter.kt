package com.btv.ui.browse

import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamChannel
import com.btv.data.store.isAdultCategoryName

/**
 * Réglages filters as they apply to Rediffusion. Its sidebar lists live
 * channels rather than categories, so each channel is judged through its
 * live category (hidden, adult, language) and through its own name, which
 * often carries a tag its category doesn't (|BE| channels inside a |FR|
 * category, for instance).
 */
internal class ReplayChannelFilter(
    liveCategories: List<XtreamCategory>,
    private val hiddenCategoryIds: Set<String>,
    private val revealedAdultCategoryIds: Set<String>,
    private val disabledPrefixes: Set<String>
) {
    private val categoryNames = liveCategories.associate { it.categoryId to it.categoryName }

    fun isVisible(channel: XtreamChannel): Boolean {
        val categoryId = channel.categoryId
        if (categoryId != null && categoryId in hiddenCategoryIds) return false
        if (isLockedAdult(channel)) return false
        val categoryName = categoryId?.let(categoryNames::get) ?: channel.categoryName
        if (categoryName != null && extractLanguagePrefix(categoryName) in disabledPrefixes) return false
        return extractLanguagePrefix(channel.name) !in disabledPrefixes
    }

    /** Adult by its category (or, failing that, by its own name) and not unlocked with the PIN. */
    private fun isLockedAdult(channel: XtreamChannel): Boolean {
        val categoryId = channel.categoryId
        if (categoryId != null && categoryId in revealedAdultCategoryIds) return false
        val categoryName = categoryId?.let(categoryNames::get) ?: channel.categoryName
        return (categoryName != null && isAdultCategoryName(categoryName)) || isAdultCategoryName(channel.name)
    }
}

/** "|BE| DAZN 1 FHD (FR)" -> "DAZN 1 FHD": what a replay channel search matches, tags left out. */
internal fun replaySearchKey(channelName: String): String =
    channelName.replace(LEADING_TAG_REGEX, "").replace(BRACKETED_REGEX, " ").trim()

private val LEADING_TAG_REGEX = Regex("""^\s*\|[^|]*\|\s*""")
private val BRACKETED_REGEX = Regex("""\([^)]*\)|\[[^\]]*\]""")
