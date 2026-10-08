package com.btv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.R
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

/** App icon + "bTV" wordmark (home header, login, startup). */
@Composable
fun BtvBrand(modifier: Modifier = Modifier, iconSize: Dp = 34.dp) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.btv_icon), contentDescription = "bTV", modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(10.dp))
        Text(
            "bTV",
            color = BtvTheme.colors.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp
        )
    }
}

/**
 * A section heading with an optional quiet second line (counts, hints):
 * strong title, discreet metadata.
 */
@Composable
fun BtvSectionTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier = modifier) {
        Text(
            title,
            style = BtvType.section,
            color = BtvTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, style = BtvType.meta, color = BtvTheme.colors.textMuted, maxLines = 1)
        }
    }
}

/** Small uppercase label above a group ("CATÉGORIES", "QUALITÉ"). */
@Composable
fun BtvOverline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = BtvType.overline, color = BtvTheme.colors.textMuted, maxLines = 1)
}

/** "1 titre" / "12 titres". */
fun countLabel(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count > 1) plural else singular}"
