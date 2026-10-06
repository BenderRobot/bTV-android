package com.btv.ui.browse

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.btv.ui.browse.components.CategoryContent
import com.btv.ui.theme.BtvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class BrowseActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteCanFavoriteOpenEpgAndReturnToRail() {
        val favorite = mutableStateOf(false)
        var epgOpens = 0
        var contentOpens = 0
        val channel = ContentItem("test-channel", "Chaîne de test")
        compose.setContent {
            BtvTheme(darkTheme = true) {
                CategoryContent(
                    sectionTitle = "Direct", contents = listOf(channel), selectedContent = channel,
                    contentSearch = "", isFocused = true, isSelectedFavorite = favorite.value,
                    onContentPreview = {}, onContentOpen = { contentOpens++ }, onSearchChanged = {},
                    onSearchCleared = {}, onToggleFavorite = { favorite.value = !favorite.value },
                    showEpgButton = true, onOpenEpg = { epgOpens++ }
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithContentDescription("Ajouter aux favoris").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithContentDescription("Retirer des favoris").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("Guide TV").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, epgOpens) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, contentOpens) }
    }
}
