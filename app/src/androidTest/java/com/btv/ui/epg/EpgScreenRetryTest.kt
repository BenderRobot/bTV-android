package com.btv.ui.epg

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.btv.ui.theme.BtvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class EpgScreenRetryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyGuideFailureFocusesRetryAndRespondsToDpadOk() {
        var retries = 0
        compose.setContent {
            BtvTheme(darkTheme = true) {
                EpgScreen(
                    channelName = "Chaîne test",
                    programs = emptyList(),
                    error = "Impossible de charger le guide TV. Réessayer.",
                    onRetry = { retries++ },
                    onBack = {}
                )
            }
        }
        compose.onNodeWithText("Programme non disponible.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Réessayer le guide TV").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
