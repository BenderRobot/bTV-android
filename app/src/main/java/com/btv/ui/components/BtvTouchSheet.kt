package com.btv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.ui.theme.BtvTheme

/**
 * Upright phone: the details panel the wide layouts keep beside their list,
 * raised from the bottom instead. [content] fills the sheet (it scrolls on
 * its own if it needs to); [actionLabel] is the main button, always visible.
 * A tap outside, or Back, closes it.
 */
@Composable
fun BtvTouchSheet(
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    actionIcon: Int? = null,
    onAction: (() -> Unit)? = null,
    heightFraction: Float = 0.82f,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = BtvTheme.colors
    BtvOverlay(onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .onTap(action = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(heightFraction)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(colors.surface)
                    .consumeTaps() // taps inside never close it
                    .navigationBarsPadding()
            ) {
                // Grab handle.
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .background(colors.textMuted.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                )
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), content = content)
                if (actionLabel != null && onAction != null) {
                    Row(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(colors.accentOnSurface, RoundedCornerShape(24.dp))
                            .onTap(action = onAction),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        actionIcon?.let {
                            Icon(painterResource(it), contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(actionLabel, color = colors.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
