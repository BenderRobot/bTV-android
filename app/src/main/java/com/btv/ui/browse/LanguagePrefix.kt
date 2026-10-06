package com.btv.ui.browse

private val LANGUAGE_PREFIX_REGEX = Regex("^\\|([A-Za-z0-9]{2,6})\\|")

/**
 * Extracts the leading "|XX|" language/region tag from a category name such
 * as "|FR| ACTION ET AVENTURE" or "|AR| ARABIC KIDS". Returns null (and thus
 * never filterable by language) for categories that don't follow this
 * convention.
 */
fun extractLanguagePrefix(categoryName: String): String? {
    return LANGUAGE_PREFIX_REGEX.find(categoryName)?.groupValues?.getOrNull(1)?.uppercase()
}
