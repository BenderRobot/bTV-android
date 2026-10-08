package com.btv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

/** Full-screen dim behind an in-tree dialog; the dialog itself owns the remote. */
@Composable
fun BtvDialogScrim(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * The dialog card shared by every modal of the app: surface fill, a
 * hairline edge, 18dp corners and generous padding. Title and message use
 * [BtvDialogTitle] / [BtvDialogMessage]; actions are [BtvButton]s.
 */
@Composable
fun BtvDialogSurface(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 520.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = BtvTheme.colors
    Column(
        modifier = modifier
            .widthIn(min = 320.dp, max = maxWidth)
            .background(colors.surface, BtvShapes.dialog)
            .border(1.dp, colors.border, BtvShapes.dialog)
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = horizontalAlignment,
        content = content
    )
}

@Composable
fun BtvDialogTitle(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(text, modifier = modifier, style = BtvType.section, color = BtvTheme.colors.textPrimary, textAlign = textAlign)
}

@Composable
fun BtvDialogMessage(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Spacer(Modifier.height(8.dp))
    Text(text, modifier = modifier, style = BtvType.body, color = BtvTheme.colors.textSecondary, textAlign = textAlign)
}
