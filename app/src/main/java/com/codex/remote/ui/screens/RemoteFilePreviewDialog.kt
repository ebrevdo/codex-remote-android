package com.codex.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codex.remote.data.security.RemoteFileLink
import kotlinx.coroutines.CancellationException

internal data class PreviewLink(val target: String, val baseDirectory: String)

@Composable
internal fun RemoteFilePreviewDialog(
    file: RemoteFileLink,
    readFile: suspend (String) -> String,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember(file) { mutableStateOf<String?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var attempt by remember(file) { mutableStateOf(0) }
    var showSource by remember(file) { mutableStateOf(file.line != null || !file.isMarkdown) }
    val reader by rememberUpdatedState(readFile)
    val scrollState = rememberScrollState()
    val lines = remember(content) { content?.lines().orEmpty() }
    val targetLine = file.line?.coerceIn(1, lines.size.coerceAtLeast(1))
    LaunchedEffect(file, attempt) {
        content = null
        error = null
        scrollState.scrollTo(0)
        try {
            content = reader(file.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not read this file."
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 820.dp).fillMaxWidth().heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.testTag("remote-file-preview")) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("File preview", style = MaterialTheme.typography.titleLarge)
                    SelectionContainer {
                        Text(file.path, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    file.line?.let { requested ->
                        val label = if (content != null && requested != targetLine) {
                            "Line $targetLine · requested $requested"
                        } else {
                            "Line $requested"
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val message = error
                    val text = content
                    when {
                        message != null -> Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                        text == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                        text.isEmpty() -> Text("This file is empty.", Modifier.padding(16.dp))
                        showSource -> SourceFileBody(lines, targetLine)
                        else -> Box(Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp)) {
                            MarkdownBody(text, Modifier.testTag("remote-file-markdown"), onOpenLink)
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (file.isMarkdown && !content.isNullOrEmpty()) {
                        TextButton(onClick = { showSource = !showSource }) {
                            Text(if (showSource) "Preview" else "Source")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (error != null) TextButton(onClick = { attempt++ }) { Text("Retry") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun SourceFileBody(lines: List<String>, targetLine: Int?) {
    // Each item is a source line, so wrapping and Markdown syntax cannot skew the jump.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (targetLine ?: 1) - 1)
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val lineNumberDigits = lines.size.toString().length
    SelectionContainer {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().testTag("remote-file-source"),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                val highlighted = index + 1 == targetLine
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .semantics { selected = highlighted }
                        .testTag("remote-file-line-${index + 1}")
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    Text(
                        (index + 1).toString().padStart(lineNumberDigits),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(line.ifEmpty { " " }, Modifier.weight(1f), style = style)
                }
            }
        }
    }
}
