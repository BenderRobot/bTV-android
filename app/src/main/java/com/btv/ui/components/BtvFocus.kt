package com.btv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import kotlinx.coroutines.delay

/**
 * The app's single focus language: a resting surface and, on focus, a
 * lifted fill, a thin accent ring and optionally a slight scale, all over
 * [BtvMotion.FOCUS_MS]. Purely visual - focus itself stays owned by each
 * call site's focusRequester / onFocusChanged / focusable chain.
 *
 * [restColor] / [focusedColor] default to transparent / surface2.
 */
fun Modifier.btvFocusSurface(
    focused: Boolean,
    shape: Shape = BtvShapes.control,
    restColor: Color = Color.Unspecified,
    focusedColor: Color = Color.Unspecified,
    focusScale: Float = 1f,
    restBorder: Color = Color.Transparent
): Modifier = composed {
    val colors = BtvTheme.colors
    val fill by animateColorAsState(
        if (focused) focusedColor.takeOrElse { colors.surface2 } else restColor.takeOrElse { Color.Transparent },
        tween(BtvMotion.FOCUS_MS),
        label = "btvFocusFill"
    )
    val ring by animateColorAsState(
        if (focused) colors.focusRing else restBorder,
        tween(BtvMotion.FOCUS_MS),
        label = "btvFocusRing"
    )
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(BtvMotion.FOCUS_MS), label = "btvFocusScale")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .background(fill, shape)
        .border(BtvDimens.focusBorder, ring, shape)
}

/** Slight lift of a focused card, drawn (not laid out): neighbours never move. */
fun Modifier.btvFocusScale(focused: Boolean, scale: Float = BtvMotion.FOCUS_SCALE): Modifier = composed {
    val value by animateFloatAsState(if (focused) scale else 1f, tween(BtvMotion.FOCUS_MS), label = "btvScale")
    graphicsLayer { scaleX = value; scaleY = value }
}

/** Short accent bar on the leading edge: marks the selected entry of a list without filling it. */
fun Modifier.btvSelectionBar(visible: Boolean): Modifier = composed {
    val color = BtvTheme.colors.focusRing
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(BtvMotion.FOCUS_MS), label = "btvSelectionBar")
    drawBehind {
        if (alpha > 0f) {
            val barHeight = size.height * 0.56f
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(0f, (size.height - barHeight) / 2f),
                size = Size(3.dp.toPx(), barHeight),
                cornerRadius = CornerRadius(1.5.dp.toPx())
            )
        }
    }
}

/**
 * requestFocus() that survives the race against the first layout pass: a
 * target not placed yet throws, so retry a few frames before giving up.
 */
suspend fun FocusRequester.requestFocusWithRetry(attempts: Int = 20, delayMs: Long = 50) {
    repeat(attempts) {
        if (runCatching { requestFocus() }.isSuccess) return
        delay(delayMs)
    }
}
