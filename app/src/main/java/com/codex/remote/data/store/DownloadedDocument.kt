package com.codex.remote.data.store

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.codex.remote.data.security.remoteFileExtension
import com.codex.remote.data.security.remoteFileName
import com.codex.remote.data.security.remoteImageMimeType
import com.codex.remote.domain.RemoteFileDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class DownloadedFile(val uri: Uri, val name: String, val mimeType: String) {
    fun openIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Stream directly to Downloads on Android 10+, with an app-owned fallback on older phones. */
internal suspend fun saveDownloadedFile(
    context: Context,
    path: String,
    download: RemoteFileDownload,
    onProgress: (Long) -> Unit,
): DownloadedFile {
    val name = remoteFileName(path)
    val mimeType = remoteImageMimeType(path)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(remoteFileExtension(path))
        ?: if (remoteFileExtension(path) in setOf("md", "markdown", "mdown")) "text/markdown" else "application/octet-stream"
    var destination: Uri? = null
    var privateFile: File? = null
    try {
        return withContext(Dispatchers.IO) {
            val uri = if (Build.VERSION.SDK_INT >= 29) {
                createDownload(context, name, mimeType).also { destination = it }
            } else {
                val directory = File(context.filesDir, "downloads/${UUID.randomUUID()}")
                if (!directory.mkdirs()) throw IOException("Could not create the download folder.")
                File(directory, name).also { privateFile = it }.let { file ->
                    FileProvider.getUriForFile(context, "${context.packageName}.download-files", file)
                }
            }
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the download destination.")
            output.use { download.writeTo(it, onProgress) }
            if (Build.VERSION.SDK_INT >= 29) {
                val published = context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                if (published != 1) throw IOException("Could not finish saving the download.")
            }
            DownloadedFile(uri, name, mimeType)
        }
    } catch (failure: Throwable) {
        // Cancellation can arrive while returning from the I/O dispatcher, after the write completed.
        withContext(NonCancellable + Dispatchers.IO) {
            destination?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            privateFile?.let { file ->
                runCatching { file.delete() }
                runCatching { file.parentFile?.delete() }
            }
        }
        throw failure
    }
}

@RequiresApi(29)
private fun createDownload(context: Context, name: String, mimeType: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Could not create a file in Downloads.")
}
