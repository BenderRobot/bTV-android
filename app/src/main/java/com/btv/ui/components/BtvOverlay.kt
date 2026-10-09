package com.btv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen layers (sheets, pickers) drawn in the app's own window, above
 * every screen. A separate dialog window sat below the status bar and spilled
 * past the bottom of a phone screen, and brought the hidden status bar back.
 */
class OverlayHost {
    private var owner: Any? by mutableStateOf(null)
    private var layer: (@Composable () -> Unit)? by mutableStateOf(null)

    internal fun show(token: Any, content: @Composable () -> Unit) {
        owner = token
        layer = content
    }

    internal fun hide(token: Any) {
        if (owner === token) {
            owner = null
            layer = null
        }
    }

    /** Placed last in the root Box, so it covers screens and the mini-player. */
    @Composable
    fun Layer() {
        layer?.invoke()
    }
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/**
 * Shows [content] full screen while this is in the composition; Back calls
 * [onDismiss]. Without a host (previews, tests) it falls back to a dialog.
 */
@Composable
fun BtvOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
        return
    }
    val token = remember { Any() }
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host, token) {
        host.show(token) {
            BackHandler { latestDismiss() }
            latestContent()
        }
        onDispose { host.hide(token) }
    }
}
