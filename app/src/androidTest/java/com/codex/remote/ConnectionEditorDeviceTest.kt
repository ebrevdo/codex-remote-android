package com.codex.remote

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ConnectionDraft
import com.codex.remote.ui.screens.ConnectionsScreen
import com.codex.remote.ui.theme.CodexRemoteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionEditorDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun saveActionsAreReachableInNarrowPortraitWithLargeTextAndKeyboard() {
        val draft = ConnectionDraft(name = "Devbox", host = "example.test", username = "developer")
        val state = mutableStateOf(AppUiState(showConnectionEditor = true, connectionDraft = draft))
        val saved = mutableListOf<Pair<ConnectionDraft, Boolean>>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                CodexRemoteTheme {
                    Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                        ConnectionsScreen(
                            state = state.value,
                            onBack = {}, onAdd = {}, onEdit = {}, onDelete = {}, onConnect = {},
                            onSave = { value, connect -> saved += value to connect },
                            onUpdateDraft = { state.value = state.value.copy(connectionDraft = it) },
                            onCloseEditor = {}, onDismissNotice = {},
                        )
                    }
                }
            }
        }
        // Focus the password field so the available viewport also has to account for the IME.
        composeRule.onNode(hasSetTextAction() and hasText("Password"))
            .performScrollTo().performClick().performTextInput("test-only-password")
        composeRule.onNodeWithText("Save").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Save & connect").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            val expected = draft.copy(password = "test-only-password")
            assertEquals(listOf(expected to false, expected to true), saved)
        }
    }
}
