package com.btv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

enum class BtvButtonStyle {
    /** The one action the screen is about ("Regarder", "Se connecter"): solid accent. */
    Primary,
    /** Other actions: quiet translucent fill. */
    Secondary,
    /** Toolbars (header, sidebar back): no fill until focused. */
    Ghost
}

/**
 * Remote-friendly button. OK / Enter / Center click on key-down (held OK
 * repeats are ignored); every other key falls through to the caller's own
 * onKeyEvent in [modifier], so screens keep their explicit D-pad routing
 * (focusRequester + Left/Right/Up/Down handlers) exactly as before.
 *
 * [active] marks a toggled state (in favorites, watched): accent icon.
 * [contentDescription] overrides the accessibility label (tests use it).
 */
@Composable
fun BtvButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtvButtonStyle = BtvButtonStyle.Secondary,
    @DrawableRes icon: Int? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val label = contentDescription ?: text
    BtvButtonContent(
        text = text,
        focused = focused,
        style = style,
        icon = icon,
        active = active,
        enabled = enabled,
        modifier = modifier
            .semantics {
                role = Role.Button
                if (label != null) this.contentDescription = label
                onClick { if (enabled) onClick(); enabled }
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusable()
            .onKeyEvent { event ->
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!isOk || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (enabled && event.nativeKeyEvent.repeatCount == 0) onClick()
                true
            }
            // Touch / mouse. Not clickable(): it would add a second, nested
            // focus target that D-pad traversal could land on.
            .pointerInput(enabled) { detectTapGestures { if (enabled) currentOnClick() } }
    )
}

/**
 * The look of [BtvButton] without its focus handling, for dialogs that
 * drive a "virtual" focus themselves (one focus target, an index in state).
 */
@Composable
fun BtvButtonContent(
    text: String?,
    focused: Boolean,
    modifier: Modifier = Modifier,
    style: BtvButtonStyle = BtvButtonStyle.Secondary,
    @DrawableRes icon: Int? = null,
    active: Boolean = false,
    enabled: Boolean = true
) {
    val colors = BtvTheme.colors
    val fillTarget = when (style) {
        BtvButtonStyle.Primary -> if (focused) com.btv.ui.theme.BtvGreenBright else com.btv.ui.theme.BtvGreen
        BtvButtonStyle.Secondary -> if (focused) colors.surface3 else colors.overlayMedium
        BtvButtonStyle.Ghost -> if (focused) colors.surface3 else Color.Transparent
    }
    val contentTarget = when {
        style == BtvButtonStyle.Primary -> colors.onAccent
        focused -> colors.textPrimary
        style == BtvButtonStyle.Ghost -> colors.textSecondary
        else -> colors.textPrimary
    }
    // Ring: accent on quiet buttons; on the solid accent one a white ring is what shows.
    val ringTarget = when {
        !focused -> Color.Transparent
        style == BtvButtonStyle.Primary -> colors.textPrimary
        else -> colors.focusRing
    }
    val fill by animateColorAsState(fillTarget, tween(BtvMotion.FOCUS_MS), label = "btnFill")
    val content by animateColorAsState(contentTarget, tween(BtvMotion.FOCUS_MS), label = "btnContent")
    val ring by animateColorAsState(ringTarget, tween(BtvMotion.FOCUS_MS), label = "btnRing")
    val scale by animateFloatAsState(if (focused) BtvMotion.FOCUS_SCALE_SMALL else 1f, tween(BtvMotion.FOCUS_MS), label = "btnScale")
    val iconOnly = text.isNullOrEmpty()

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .heightIn(min = BtvDimens.controlHeight)
            .widthIn(min = if (iconOnly) BtvDimens.iconButtonSize else 0.dp)
            .background(fill, BtvShapes.control)
            .border(BtvDimens.focusBorder, ring, BtvShapes.control)
            .padding(horizontal = if (iconOnly) 11.dp else 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (active && style != BtvButtonStyle.Primary) colors.accentOnSurface else content,
                modifier = Modifier.size(18.dp)
            )
        }
        if (!iconOnly) {
            Text(text!!, style = BtvType.label, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
