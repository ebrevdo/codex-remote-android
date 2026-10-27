package com.codex.remote

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.store.saveDownloadedFile
import com.codex.remote.domain.RemoteFileDownload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadedFileDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun automaticDownloadsPublishOnlyCompleteFilesAndNeverOverwriteAnotherDownload() = runBlocking {
        val name = "codex-download-${UUID.randomUUID()}.png"
        val firstBytes = byteArrayOf(0, 1, 0xFF.toByte(), 13, 10)
        val secondBytes = byteArrayOf(5, 4, 3, 2, 1)
        val first = saveDownloadedFile(context, "/repo/$name", RemoteFileDownload { output, _ -> output.write(firstBytes) }) {}
        val second = saveDownloadedFile(context, "/repo/$name", RemoteFileDownload { output, _ -> output.write(secondBytes) }) {}
        try {
            assertNotEquals(first.uri, second.uri)
            assertEquals("image/png", first.mimeType)
            context.contentResolver.openInputStream(first.uri)!!.use { assertArrayEquals(firstBytes, it.readBytes()) }
            context.contentResolver.openInputStream(second.uri)!!.use { assertArrayEquals(secondBytes, it.readBytes()) }
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.query(first.uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        } finally {
            context.contentResolver.delete(first.uri, null, null)
            context.contentResolver.delete(second.uri, null, null)
        }
    }

    @Test
    fun privateDownloadProviderOpensDownloadsAndRejectsOtherPrivateFiles() {
        val directory = File(context.filesDir, "downloads/provider-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "test.txt").apply { writeText("saved download") }
        val outside = File(context.filesDir, "outside-downloads.txt")
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.download-files", file)
            context.contentResolver.openInputStream(uri)!!.use {
                assertEquals("saved download", it.bufferedReader().readText())
            }
            val failure = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.download-files", outside)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test
    fun cancellationAndWriteFailureRemoveIncompleteDownloads() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val canceledName = "codex-canceled-${UUID.randomUUID()}.bin"
        val failingName = "codex-failed-${UUID.randomUUID()}.bin"
        val writing = CompletableDeferred<Unit>()
        val transfer = launch {
            saveDownloadedFile(context, "/repo/$canceledName", RemoteFileDownload { output, _ ->
                output.write(byteArrayOf(0, 1, 2))
                writing.complete(Unit)
                awaitCancellation()
            }) {}
        }
        withTimeout(30_000) { writing.await() }
        transfer.cancelAndJoin()
        assertNoDownloadNamed(canceledName)
        val failure = runCatching {
            saveDownloadedFile(context, "/repo/$failingName", RemoteFileDownload { output, _ ->
                output.write(byteArrayOf(0, 1, 2))
                throw IOException("Storage full")
            }) {}
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertNoDownloadNamed(failingName)
    }

    private fun assertNoDownloadNamed(name: String) {
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(name), null,
        )!!.use { cursor ->
            val leaked = mutableListOf<Long>()
            while (cursor.moveToNext()) leaked += cursor.getLong(0)
            // Clean up even if the regression fails.
            leaked.forEach { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it), null, null) }
            assertTrue("Incomplete download remained in MediaStore", leaked.isEmpty())
        }
    }
}
