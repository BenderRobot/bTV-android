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
import com.btv.ui.theme.BtvGreen
import com.btv.ui.theme.BtvTheme

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
    val colors = BtvTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
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
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .background(colors.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 32.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Quitter l'application ?", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                DialogButton("Annuler", focusIndex == 0)
                Spacer(Modifier.width(14.dp))
                DialogButton("Quitter", focusIndex == 1)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, focused: Boolean) {
    val colors = BtvTheme.colors
    Box(
        modifier = Modifier
            .background(if (focused) BtvGreen else colors.surface2, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) BtvGreen else colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 26.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = if (focused) Color.White else colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
