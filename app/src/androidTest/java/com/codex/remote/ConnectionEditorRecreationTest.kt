package com.codex.remote

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.domain.AppServerMode
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.ConnectionDraft
import com.codex.remote.domain.RemotePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a clean test device, as MainActivity restores saved connections at startup. */
@RunWith(AndroidJUnit4::class)
class ConnectionEditorRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityRecreationRetainsEveryDraftFieldAndCancelClearsIt() {
        val initial = ConnectionDraft(
            port = "2222", username = "developer", authType = AuthType.PRIVATE_KEY,
            password = "test-password", privateKey = "test-only-key\nsecond line",
            passphrase = "test-passphrase", clearHostKeyFingerprint = true,
            platform = RemotePlatform.POSIX, appServerMode = AppServerMode.DAEMON,
        )
        lateinit var viewModel: AppViewModel
        composeRule.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[AppViewModel::class.java]
            viewModel.apply {
                editConnection()
                updateConnectionDraft(initial)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { !viewModel.state.value.isRestoringLastConnection }
        composeRule.onNode(hasSetTextAction() and hasText("Display name"))
            .performScrollTo().performTextInput("Unsaved host")
        composeRule.onNode(hasSetTextAction() and hasText("Host"))
            .performScrollTo().performTextInput("example.test")
        val expected = initial.copy(name = "Unsaved host", host = "example.test")
        // Rotation follows this same Activity recreation/ViewModel retention path.
        composeRule.activityRule.scenario.recreate()
        composeRule.onNode(hasSetTextAction() and hasText("Display name"))
            .performScrollTo().assertTextContains("Unsaved host")
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals(expected, ViewModelProvider(activity)[AppViewModel::class.java].state.value.connectionDraft)
        }
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity)[AppViewModel::class.java].apply {
                assertNull(state.value.connectionDraft)
                editConnection()
                assertEquals(ConnectionDraft(), state.value.connectionDraft)
                closeEditor()
            }
        }
    }
}
