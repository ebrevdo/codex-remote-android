package com.codex.remote.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.remote.domain.RemoteFileUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun uploadComposerFile(
    context: Context,
    uri: Uri,
    upload: RemoteFileUpload,
    onProgress: (displayName: String, bytes: Long) -> Unit,
): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
    }?.takeIf(String::isNotBlank) ?: "file"
    resolver.openInputStream(uri)?.use { input ->
        upload.writeFrom(displayName, input) { onProgress(displayName, it) }
    } ?: error("Unable to open the selected file.")
}

@Composable
internal fun ComposerFileUploadProgress(displayName: String, bytes: Long, onCancel: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().testTag("composer-file-upload")) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Uploading $displayName · ${Formatter.formatFileSize(context, bytes)}",
                modifier = Modifier.weight(1f).padding(end = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
