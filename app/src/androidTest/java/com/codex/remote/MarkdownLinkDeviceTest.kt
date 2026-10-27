package com.codex.remote

import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.ui.screens.MarkdownBody
import com.codex.remote.ui.theme.CodexRemoteTheme
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tables.TableTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownLinkDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tapsOpenHeaderLinksAndFileAndHttpsLinksInDifferentTableColumns() {
        val opened = mutableListOf<String>()
        show("""
            | [Header](https://example.com/header) | Files | Web |
            | :--- | :---: | ---: |
            | Plain cell | [Report](/repo/report.md:20) | [Website](https://example.com/report) |
        """.trimIndent(), opened::add)

        tapCell(rowIndex = 0, columnIndex = 0)
        tapCell(rowIndex = 1, columnIndex = 1)
        tapCell(rowIndex = 1, columnIndex = 2)
        composeRule.runOnIdle {
            assertEquals(listOf("https://example.com/header", "/repo/report.md:20", "https://example.com/report"), opened)
        }
    }

    @Test
    fun wrappedTableLinksAndOrdinaryLinksUseTheCurrentCallback() {
        val callbackVersion = mutableStateOf(0)
        val opened = mutableListOf<String>()
        val markdown = """
            [Outside](https://example.com/outside)

            | File | Notes |
            | --- | --- |
            | [A report with a long label that wraps onto several lines in this narrow table column](/repo/long.md:8) | Plain cell |
        """.trimIndent()
        composeRule.setContent {
            val version = callbackVersion.value
            CodexRemoteTheme {
                MarkdownBody(markdown, Modifier.padding(16.dp).testTag("markdown")) { opened += "$version:$it" }
            }
        }
        composeRule.runOnIdle { callbackVersion.value = 1 }
        tapCell(rowIndex = 1, columnIndex = 0, lastLine = true)
        val point = composeRule.runOnIdle {
            val view = markdownView()
            val line = view.layout.getLineForOffset(view.text.indexOf("Outside"))
            Offset(view.layout.getPrimaryHorizontal(3), (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f)
        }
        composeRule.onNodeWithTag("markdown").performTouchInput { click(point) }
        composeRule.runOnIdle {
            assertEquals(listOf("1:/repo/long.md:8", "1:https://example.com/outside"), opened)
        }
    }

    private fun show(markdown: String, onOpenLink: (String) -> Unit) {
        composeRule.setContent {
            CodexRemoteTheme {
                MarkdownBody(markdown, Modifier.padding(16.dp).testTag("markdown"), onOpenLink)
            }
        }
    }

    private fun tapCell(rowIndex: Int, columnIndex: Int, lastLine: Boolean = false) {
        val point = composeRule.runOnIdle {
            val view = markdownView()
            val text = view.text as Spanned
            val rows = text.getSpans(0, text.length, TableRowSpan::class.java).sortedBy(text::getSpanStart)
            val row = rows[rowIndex]
            val width = row.cellWidth()
            assertTrue("Table must be drawn before tapping", width > 0)
            val cell = checkNotNull(row.findLayoutForHorizontalOffset(columnIndex * width))
            val line = if (lastLine) cell.lineCount - 1 else 0
            if (lastLine) assertTrue("Regression must cover a wrapped link: ${cell.text}", cell.lineCount > 1)
            val rowLine = view.layout.getLineForOffset(text.getSpanStart(row))
            val rowTop = view.layout.getLineTop(rowLine)
            val rowHeight = view.layout.getLineBottom(rowLine) - rowTop
            val cellHeight = generateSequence(0) { it + 1 }
                .map { row.findLayoutForHorizontalOffset(it * width) }
                .takeWhile { it != null }
                .maxOf { checkNotNull(it).height }
            val padding = TableTheme.create(view.context).tableCellPadding()
            Offset(
                columnIndex * width + padding + (cell.getLineLeft(line) + cell.getLineRight(line)) / 2f,
                rowTop + padding + (rowHeight - cellHeight) / 4f + (cell.getLineTop(line) + cell.getLineBottom(line)) / 2f,
            )
        }
        // Send real touch events through AndroidView, rather than calling a span's onClick directly.
        composeRule.onNodeWithTag("markdown").performTouchInput { click(point) }
    }

    private fun markdownView(): TextView = composeRule.activity.window.decorView.textViews()
        .single { it.text is Spanned && (it.text as Spanned).getSpans(0, it.text.length, TableRowSpan::class.java).isNotEmpty() }

    private fun View.textViews(): Sequence<TextView> = sequence {
        if (this@textViews is TextView) yield(this@textViews)
        if (this@textViews is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).textViews())
        }
    }
}
