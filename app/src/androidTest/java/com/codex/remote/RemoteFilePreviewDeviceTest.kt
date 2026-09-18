package com.codex.remote

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.data.security.parseRemoteFileLink
import com.codex.remote.ui.screens.RemoteFilePreviewDialog
import com.codex.remote.ui.theme.CodexRemoteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteFilePreviewDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lineReferencesJumpToSourceAndSwitchToMarkdownWithoutAnotherRead() {
        val source = (1..200).joinToString("\r\n") { line ->
            when {
                line == 120 -> "**Target source line**"
                line % 5 == 0 -> ""
                else -> "Paragraph $line " + "wrapped content ".repeat(15)
            }
        }
        var reads = 0
        show("/repo/report.md:120:7") { path ->
            assertEquals("/repo/report.md", path)
            reads++
            source
        }
        composeRule.onNodeWithText("Line 120").assertIsDisplayed()
        composeRule.onNodeWithTag("remote-file-line-120").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("**Target source line**").assertIsDisplayed()
        composeRule.onNodeWithTag("remote-file-line-1").assertDoesNotExist()
        composeRule.onNodeWithText("Preview").performClick()
        composeRule.onNodeWithTag("remote-file-markdown").assertExists()
        composeRule.onNodeWithTag("remote-file-source").assertDoesNotExist()
        composeRule.onNodeWithText("Source").performClick()
        composeRule.onNodeWithTag("remote-file-line-120").assertIsDisplayed().assertIsSelected()
        composeRule.runOnIdle { assertEquals(1, reads) }
    }

    @Test
    fun referencesPastTheEndLandOnTheLastLine() {
        show("/repo/Main.kt#L2147483647") {
            (1..80).joinToString("\n") { "val value$it = $it" }
        }
        composeRule.onNodeWithText("Line 80 · requested 2147483647").assertIsDisplayed()
        composeRule.onNodeWithTag("remote-file-line-80").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("val value80 = 80").assertIsDisplayed()
        composeRule.onNodeWithText("Preview").assertDoesNotExist()
    }

    private fun show(target: String, readFile: suspend (String) -> String) {
        val file = checkNotNull(parseRemoteFileLink(target, "/repo"))
        composeRule.setContent {
            CodexRemoteTheme(darkTheme = false) {
                RemoteFilePreviewDialog(file, readFile, onOpenLink = {}, onDismiss = {})
            }
        }
    }
}
