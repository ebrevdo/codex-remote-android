package com.codex.remote.ui.screens

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.codex.remote.data.store.DownloadedFile
import com.codex.remote.data.store.saveDownloadedFile
import com.codex.remote.domain.RemoteFileDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun RemoteFileDownloadAction(path: String, prepareDownload: (String) -> RemoteFileDownload) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prepare by rememberUpdatedState(prepareDownload)
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadedBytes by remember { mutableLongStateOf(0) }
    var downloadedFile by remember { mutableStateOf<DownloadedFile?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var hasError by remember { mutableStateOf(false) }

    fun startDownload() {
        message = null
        hasError = false
        downloadedBytes = 0
        menuOpen = false
        val download = try {
            prepare(path)
        } catch (error: Exception) {
            hasError = true
            message = "Download failed: ${error.message ?: "Could not save this file."}"
            return
        }
        downloadJob = scope.launch {
            try {
                downloadedFile = saveDownloadedFile(context, path, download) { downloadedBytes = it }
                message = "Download saved."
                menuOpen = true
            } catch (cancelled: CancellationException) {
                message = "Download canceled."
                throw cancelled
            } catch (error: Exception) {
                hasError = true
                message = "Download failed: ${error.message ?: "Could not save this file."}"
            } finally {
                downloadJob = null
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (downloadJob != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(
                    enabled = downloadJob == null,
                    modifier = Modifier.testTag("remote-file-download"),
                    onClick = { if (downloadedFile == null) startDownload() else menuOpen = true },
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Text(if (downloadedFile == null) "Download" else "Downloaded", Modifier.padding(start = 8.dp))
                    if (downloadedFile != null) Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    downloadedFile?.let { file ->
                        DropdownMenuItem(text = { Text("Open") }, modifier = Modifier.testTag("remote-file-open"), onClick = {
                            menuOpen = false
                            try {
                                context.startActivity(file.openIntent())
                            } catch (_: ActivityNotFoundException) {
                                hasError = true
                                message = "No installed app can open this file type."
                            } catch (error: Exception) {
                                hasError = true
                                message = "Could not open the download: ${error.message ?: "Try again."}"
                            }
                        })
                    }
                    DropdownMenuItem(text = { Text("Download again") }, onClick = { startDownload() })
                }
            }
            if (downloadJob != null) {
                Text(
                    "${Formatter.formatFileSize(context, downloadedBytes)} downloaded",
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { downloadJob?.cancel() }) { Text("Cancel") }
            }
        }
        message?.let {
            Text(
                it,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
