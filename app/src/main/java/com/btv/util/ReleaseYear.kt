package com.btv.util

private val YEAR = Regex("""(?<!\d)(19|20)\d{2}(?!\d)""")

/**
 * The year of an Xtream release date, whatever its format: panels send
 * "2025-10-01", "01 Oct 2025", "01/10/2025" or just "2025". Taking the first
 * four characters read "01 O" from the second form. Null when there is none.
 */
fun extractYear(raw: String?): String? = raw?.let { YEAR.find(it)?.value }
