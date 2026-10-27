package com.codex.remote

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.security.parseRemoteFileLink
import com.codex.remote.domain.RemoteFileDownload
import com.codex.remote.ui.screens.RemoteFilePreviewDialog
import com.codex.remote.ui.theme.CodexRemoteTheme
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteFilePreviewDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun binaryPreviewFailureStillDownloadsImmediatelyAndOffersOpen() {
        assertAutomaticDownload("/repo/archive.zip", "This file is not UTF-8 text.")
    }

    @Test
    fun oversizedPreviewFailureStillDownloadsImmediately() {
        assertAutomaticDownload("/repo/large.txt", "File is too large to preview (256 KiB limit).")
    }

    @Test
    fun readableTextDownloadsTheWholeFileWithoutTheLineSuffix() {
        assertAutomaticDownload("/repo/report.md:20", null)
    }

    private fun assertAutomaticDownload(target: String, previewError: String?) {
        val paths = mutableListOf<String>()
        val bytes = ByteArray(300_001) { it.toByte() }
        val file = checkNotNull(parseRemoteFileLink(target, "/repo"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var opened: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            show(target, prepareDownload = { path ->
                paths += path
                RemoteFileDownload { output, onProgress ->
                    output.write(bytes)
                    onProgress(bytes.size.toLong())
                }
            }) {
                if (previewError != null) throw IOException(previewError)
                "# Readable text"
            }
            if (previewError != null) composeRule.onNodeWithText(previewError).assertIsDisplayed()
            composeRule.onNodeWithTag("remote-file-download").assertIsEnabled().performClick()
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText("Download saved.").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("remote-file-open").assertIsDisplayed().performClick()
            val intent = composeRule.runOnIdle { checkNotNull(opened) }
            assertEquals(listOf(file.path), paths)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data?.scheme)
            assertEquals(checkNotNull(intent.data), intent.clipData?.getItemAt(0)?.uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            composeRule.activity.contentResolver.openInputStream(checkNotNull(intent.data))!!.use {
                assertArrayEquals(bytes, it.readBytes())
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            opened?.data?.let { composeRule.activity.contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun svgClickLoadsAnImageInsteadOfReadingItAsText() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="red"/></svg>"""
        var requestedPath: String? = null
        show("/repo/plot.svg", prepareDownload = { path ->
            requestedPath = path
            RemoteFileDownload { output, _ -> output.write(svg.toByteArray()) }
        }) { error("Image previews must not request text decoding") }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("remote-file-image").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("remote-file-image").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("/repo/plot.svg", requestedPath) }
        composeRule.onNodeWithTag("remote-file-download").assertIsEnabled()
    }

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

    private fun show(
        target: String,
        prepareDownload: (String) -> RemoteFileDownload = { error("Unexpected download") },
        readFile: suspend (String) -> String,
    ) {
        val file = checkNotNull(parseRemoteFileLink(target, "/repo"))
        composeRule.setContent {
            CodexRemoteTheme(darkTheme = false) {
                RemoteFilePreviewDialog(file, readFile, prepareDownload, onOpenLink = {}, onDismiss = {})
            }
        }
    }
}
