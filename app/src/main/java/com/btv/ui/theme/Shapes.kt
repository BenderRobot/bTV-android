package com.btv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Corner radii: one per role, so every screen rounds the same way. */
object BtvShapes {
    /** Badges, progress bars. */
    val small = RoundedCornerShape(4.dp)
    /** Buttons, fields, list rows, sidebar entries. */
    val control = RoundedCornerShape(8.dp)
    /** Posters and cards. */
    val card = RoundedCornerShape(10.dp)
    /** Side panels and the mini-player. */
    val panel = RoundedCornerShape(12.dp)
    val dialog = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(50)
}
