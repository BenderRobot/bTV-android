package com.btv.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale of the TV UI, read from 3 m away. Titles are strong, metadata
 * stays quiet: only titles are bold, everything else is Normal or Medium.
 * Colors are left to the call site (BtvTheme.colors), so one style serves
 * both themes and text drawn over artwork.
 */
object BtvType {
    /** Hero title over artwork. */
    val hero = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    /** Screen headline ("Que souhaitez-vous regarder ?"). */
    val display = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    /** Section and panel titles. */
    val section = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    /** Card, row and dialog item titles. */
    val title = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    /** Buttons and navigation entries. */
    val label = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** Year, rating, duration, counts... */
    val meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    /** Small uppercase overline ("QUALITÉ", "CATÉGORIES"). */
    val overline = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

/** Material defaults mapped onto [BtvType], for the few Material components left. */
internal val BtvMaterialTypography = Typography(
    headlineMedium = BtvType.display,
    titleLarge = BtvType.section,
    titleMedium = BtvType.title,
    bodyLarge = BtvType.body,
    bodyMedium = BtvType.body,
    labelLarge = BtvType.label,
    labelMedium = BtvType.meta,
    labelSmall = BtvType.overline
)
