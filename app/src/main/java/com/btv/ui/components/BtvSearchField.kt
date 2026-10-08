package com.btv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.R
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme

/**
 * The search field of every catalogue screen: compact, dark, a hairline
 * edge at rest and the accent ring once the remote is on it.
 *
 * BasicTextField rather than Material3's TextField, whose 56dp minimum
 * height cannot be overridden.
 *
 * D-pad behaviour, port of Tizen's search bar (js/input.js): the
 * FocusRequester lands on the text field itself (a wrapping Box would never
 * raise the software keyboard). Up/Down only move the highlight here; only
 * Center/Enter makes the field editable and opens the keyboard - Android
 * pops the IME as soon as an editable field gains focus, so the field stays
 * `readOnly` until then. Back is two-stage like Tizen's blur() guard: the
 * first Back only closes the keyboard (what was typed stays), a second one
 * hands off to [onBack].
 */
@Composable
fun BtvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
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

    // Back to plain D-pad navigation: the Fire TV keyboard closes itself on
    // its validate key without any Back reaching this field, which used to
    // leave it "editable" and swallowing Up/Down until Back was pressed.
    fun stopEditing() {
        editable = false
        keyboardController?.hide()
        imeShown = false
    }

    // Touch: a tap starts typing at once (the remote needs OK first).
    fun startEditing() {
        editable = true
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
        imeShown = true
    }

    val ring by animateColorAsState(
        if (isFocused) colors.focusRing else colors.border,
        tween(BtvMotion.FOCUS_MS),
        label = "searchRing"
    )
    val fill by animateColorAsState(
        if (isFocused) colors.surface2 else colors.surface,
        tween(BtvMotion.FOCUS_MS),
        label = "searchFill"
    )

    Row(
        modifier = modifier
            .heightIn(min = BtvDimens.searchHeight)
            .onTapObserved { startEditing() }
            .background(fill, BtvShapes.control)
            .border(if (isFocused) BtvDimens.focusBorder else BtvDimens.hairline, ring, BtvShapes.control)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_search),
            contentDescription = null,
            tint = if (isFocused) colors.textPrimary else colors.textMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = colors.textMuted,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = fontSize),
                singleLine = true,
                readOnly = !editable,
                cursorBrush = SolidColor(colors.focusRing),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                    // Validating the search goes straight to the results.
                    stopEditing()
                    onDpadDown?.invoke()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        isFocused = state.isFocused
                        onFocusChanged(state.isFocused)
                        if (!state.isFocused) {
                            imeShown = false
                            editable = false
                        }
                    }
                    // onPreviewKeyEvent (not onKeyEvent): once this field holds
                    // focus, BasicTextField's own arrow handling would swallow
                    // Up/Down before a plain onKeyEvent ever saw them.
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (keyEvent.key) {
                            // Single line: Up/Down mean nothing to the text, so they
                            // always leave typing and move on.
                            Key.DirectionUp -> {
                                if (editable) stopEditing()
                                onDpadUp?.invoke() ?: false
                            }
                            Key.DirectionDown -> {
                                if (editable) stopEditing()
                                onDpadDown?.invoke() ?: false
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                editable = true
                                keyboardController?.show()
                                imeShown = true
                                true
                            }
                            Key.Back -> {
                                if (imeShown || editable) {
                                    stopEditing()
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
}
