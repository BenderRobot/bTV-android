package com.btv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.btv.ui.components.BtvButtonContent
import com.btv.ui.components.BtvButtonStyle
import com.btv.ui.components.BtvDialogMessage
import com.btv.ui.components.BtvDialogScrim
import com.btv.ui.components.BtvDialogSurface
import com.btv.ui.components.BtvDialogTitle
import com.btv.ui.components.consumeTaps
import com.btv.ui.components.onTap

/**
 * Port of Tizen's exit-app dialog (js/modals.js openExitAppDialog): Back on
 * the home screen asks before leaving. "Annuler" is focused first so a
 * reflex double press never quits; any arrow toggles between the two
 * buttons and Back cancels. The system Home button is never intercepted.
 */
@Composable
fun ExitAppDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    var focusIndex by remember { mutableIntStateOf(0) } // 0 = Annuler, 1 = Quitter
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }
    BtvDialogScrim(
        modifier = Modifier
            .onTap { onCancel() }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        focusIndex = 1 - focusIndex
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (focusIndex == 1) onConfirm() else onCancel()
                        true
                    }
                    Key.Back -> { onCancel(); true }
                    else -> false
                }
            }
    ) {
        BtvDialogSurface(maxWidth = 440.dp, horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.consumeTaps()) {
            BtvDialogTitle("Quitter l'application ?", textAlign = TextAlign.Center)
            BtvDialogMessage("L’application bTV va se fermer.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // "Annuler" is the safe default; whichever has the focus is the
                // solid one, so the choice OK will make is never ambiguous.
                BtvButtonContent(
                    "Annuler", focused = focusIndex == 0,
                    style = if (focusIndex == 0) BtvButtonStyle.Primary else BtvButtonStyle.Secondary,
                    modifier = Modifier.onTap { onCancel() }
                )
                BtvButtonContent(
                    "Quitter", focused = focusIndex == 1,
                    style = if (focusIndex == 1) BtvButtonStyle.Primary else BtvButtonStyle.Secondary,
                    modifier = Modifier.onTap { onConfirm() }
                )
            }
        }
    }
}
