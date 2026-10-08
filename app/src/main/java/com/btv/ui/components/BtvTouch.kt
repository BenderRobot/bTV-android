package com.btv.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/*
 * Touch support (tablets, phones): the app is driven by the remote first, so
 * these never add a focus target - a tap or a long press just runs the same
 * action OK would. On a TV they simply never fire.
 */

/** Tap (and optional long press) runs [action]; remote keys are untouched. */
fun Modifier.onTap(onLongPress: (() -> Unit)? = null, action: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(action)
    val long by rememberUpdatedState(onLongPress)
    pointerInput(onLongPress != null) {
        detectTapGestures(
            onLongPress = if (onLongPress != null) { _ -> long?.invoke() } else null,
            onTap = { tap() }
        )
    }
}

/**
 * Sees a tap before the children do and lets it through: for text fields,
 * whose own handling would otherwise swallow it.
 */
fun Modifier.onTapObserved(action: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (waitForUpOrCancellation(PointerEventPass.Initial) != null) tap()
        }
    }
}

/** Absorbs taps on a panel so they don't reach the scrim/screen behind it. */
fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }
