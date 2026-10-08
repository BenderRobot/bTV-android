package com.btv.ui.parental

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.btv.ui.components.BtvDialogMessage
import com.btv.ui.components.BtvDialogScrim
import com.btv.ui.components.BtvDialogSurface
import com.btv.ui.components.BtvDialogTitle
import com.btv.ui.components.consumeTaps
import com.btv.ui.components.onTap
import com.btv.ui.theme.BtvDanger
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

private val DIGIT_KEYS = mapOf(
    Key.Zero to 0, Key.One to 1, Key.Two to 2, Key.Three to 3, Key.Four to 4,
    Key.Five to 5, Key.Six to 6, Key.Seven to 7, Key.Eight to 8, Key.Nine to 9,
    Key.NumPad0 to 0, Key.NumPad1 to 1, Key.NumPad2 to 2, Key.NumPad3 to 3, Key.NumPad4 to 4,
    Key.NumPad5 to 5, Key.NumPad6 to 6, Key.NumPad7 to 7, Key.NumPad8 to 8, Key.NumPad9 to 9
)

/**
 * PIN entry made for a Fire TV remote, which has no digit keys: Up/Down
 * turn the current digit, Right or OK keeps it and moves on, Left goes back,
 * Back cancels. Digit keys (other remotes, keyboards) type directly. Digits
 * already entered are masked.
 */
@Composable
fun PinDialog(prompt: PinPrompt, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val digits = remember(prompt.serial) { mutableStateListOf<Int>() }
    var current by remember(prompt.serial) { mutableIntStateOf(0) }
    var submitted by remember(prompt.serial) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(prompt.serial) {
        repeat(20) {
            try {
                focusRequester.requestFocus()
                return@LaunchedEffect
            } catch (_: IllegalStateException) {
                kotlinx.coroutines.delay(30)
            }
        }
    }

    fun keep(digit: Int) {
        if (submitted || digits.size >= PIN_LENGTH) return
        digits.add(digit)
        current = 0
        if (digits.size == PIN_LENGTH) {
            submitted = true
            onSubmit(digits.joinToString(""))
        }
    }

    val colors = BtvTheme.colors
    val isTv = com.btv.ui.theme.LocalIsTv.current
    BtvDialogScrim(
        Modifier
            .consumeTaps()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent true
                DIGIT_KEYS[event.key]?.let { keep(it); return@onKeyEvent true }
                when (event.key) {
                    Key.DirectionUp -> current = (current + 1) % 10
                    Key.DirectionDown -> current = (current + 9) % 10
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> keep(current)
                    Key.DirectionLeft, Key.Backspace, Key.Delete ->
                        if (digits.isNotEmpty() && !submitted) current = digits.removeAt(digits.lastIndex)
                    Key.Back, Key.Escape -> onCancel()
                }
                true // the dialog owns the remote while it is open
            }
    ) {
        BtvDialogSurface(maxWidth = 460.dp, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(44.dp).background(colors.overlayMedium, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(com.btv.R.drawable.ic_lucide_lock),
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            BtvDialogTitle(prompt.title, textAlign = TextAlign.Center)
            BtvDialogMessage(prompt.message, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(PIN_LENGTH) { index ->
                    val isCurrent = index == digits.size && !submitted
                    Box(
                        Modifier
                            .size(width = 54.dp, height = 66.dp)
                            .background(if (isCurrent) colors.surface3 else colors.surface2, BtvShapes.card)
                            .border(BtvDimens.focusBorder, if (isCurrent) colors.focusRing else Color.Transparent, BtvShapes.card),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when {
                                index < digits.size -> "•"
                                isCurrent -> if (isTv) current.toString() else "–"
                                else -> "–"
                            },
                            color = if (isCurrent) colors.accentOnSurface else colors.textPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            prompt.error?.let {
                Text(it, color = BtvDanger, style = BtvType.label)
                Spacer(Modifier.height(10.dp))
            }
            if (isTv) {
                Text(
                    "▲▼ choisir le chiffre  ·  OK valider  ·  ◀ corriger  ·  Retour annuler",
                    color = colors.textMuted, style = BtvType.meta, textAlign = TextAlign.Center
                )
            } else {
                // Touch: a keypad instead of the remote's digit picker.
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "Annuler"))
                    .forEach { keys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            keys.forEach { key ->
                                com.btv.ui.components.BtvButtonContent(
                                    text = key,
                                    focused = false,
                                    modifier = Modifier.width(96.dp).onTap {
                                        when (key) {
                                            "⌫" -> if (digits.isNotEmpty() && !submitted) digits.removeAt(digits.lastIndex)
                                            "Annuler" -> onCancel()
                                            else -> keep(key.toInt())
                                        }
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
            }
        }
    }
}
