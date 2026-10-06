package com.btv.ui.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.ui.theme.BtvGreen
import com.btv.ui.theme.BtvTheme

/**
 * Search field without Material3's TextField, whose built-in 56dp minimum
 * height cannot be overridden and was making every search box on this
 * screen look oversized. BasicTextField has no such floor.
 *
 * D-pad navigation/keyboard invocation port of Tizen's search bar
 * (js/input.js): there's no touch/mouse here, so the FocusRequester must
 * land directly on the actual text field (not a wrapping Box) for the
 * software keyboard to appear at all. D-pad Up/Down only move the
 * highlighted zone onto this field (no keyboard yet) - exactly like
 * Tizen's browseFocusZone/browseCatIndex reaching the search bar without
 * calling `.focus()`; only Center/Enter actually opens the keyboard
 * (Tizen: `document.getElementById('browse-search').focus()` in
 * handleEnter, not on mere navigation). Back is two-stage exactly like
 * Tizen's `activeSearchInput.blur()` guard - the first Back only dismisses
 * the keyboard (so pausing mid-search never wipes out what's typed so
 * far), a second Back (keyboard already closed) hands off to [onBack].
 *
 * Android auto-shows the soft keyboard the instant an editable text field
 * gains focus, regardless of whether `keyboardController.show()` is ever
 * called - so merely landing D-pad focus here (no Enter yet) would still
 * pop the IME. The field stays `readOnly` until Center/Enter explicitly
 * marks it editable, matching Tizen's input staying inert until `.focus()`
 * runs in handleEnter.
 */
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 15.sp,
    onFocusChanged: (Boolean) -> Unit = {},
    onDpadUp: (() -> Boolean)? = null,
    onDpadDown: (() -> Boolean)? = null,
    onBack: (() -> Boolean)? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }
    var imeShown by remember { mutableStateOf(false) }
    var editable by remember { mutableStateOf(false) }
    val colors = BtvTheme.colors

    Box(
        modifier = modifier
            .background(colors.bgApp, RoundedCornerShape(8.dp))
            .border(
                2.dp,
                if (isFocused) BtvGreen else colors.border,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, color = colors.textMuted, fontSize = fontSize)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = fontSize),
            singleLine = true,
            readOnly = !editable,
            cursorBrush = SolidColor(BtvGreen),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    onFocusChanged(state.isFocused)
                    if (!state.isFocused) {
                        imeShown = false
                        editable = false
                    }
                }
                // onPreviewKeyEvent (not onKeyEvent): once this field genuinely
                // holds focus, BasicTextField's own default arrow-key handling
                // intercepts Up/Down for its internal focus-search before a
                // plain onKeyEvent modifier ever sees them - silently
                // swallowing D-pad Up/Down with no visible effect. Preview
                // dispatch runs top-down, ahead of that default handling, so
                // this always gets first refusal on the key.
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (keyEvent.key) {
                        Key.DirectionUp -> if (editable) false else onDpadUp?.invoke() ?: false
                        Key.DirectionDown -> if (editable) false else onDpadDown?.invoke() ?: false
                        Key.DirectionCenter, Key.Enter -> {
                            editable = true
                            keyboardController?.show()
                            imeShown = true
                            true
                        }
                        Key.Back -> {
                            if (imeShown || editable) {
                                editable = false
                                keyboardController?.hide()
                                imeShown = false
                                true
                            } else {
                                onBack?.invoke() ?: false
                            }
                        }
                        else -> false
                    }
                }
        )
    }
}
