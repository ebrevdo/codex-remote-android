package com.codex.remote.data.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.SessionFactory
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPEngine
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Downloads raw bytes on a separate channel, with no file-size or total-duration limit. */
internal suspend fun downloadRemoteFile(
    ssh: SSHClient,
    path: String,
    output: OutputStream,
    onProgress: (Long) -> Unit,
) {
    val context = currentCoroutineContext()
    runInterruptible(Dispatchers.IO) {
        ssh.startSession().use { session ->
            val factory = object : SessionFactory by ssh {
                override fun startSession() = session
            }
            SFTPEngine(factory).use { sftp ->
                // This is a per-request stall timeout, not a deadline for the download.
                sftp.timeoutMs = 15_000
                sftp.init()
                checkDownloadFile(sftp.stat(path))
                sftp.open(path).use { file ->
                    checkDownloadFile(file.fetchAttributes())
                    // Fixed read-ahead bounds memory while avoiding one network round trip per chunk.
                    file.ReadAheadRemoteFileInputStream(8).use { input ->
                        copyDownload(input, output, onProgress) { context.ensureActive() }
                    }
                }
            }
        }
    }
}

private fun checkDownloadFile(attributes: FileAttributes) {
    if (attributes.type != FileMode.Type.REGULAR) throw IOException("Only regular files can be downloaded.")
}

/** The caller owns both streams. Byte counts and SSHJ's file offsets are 64-bit. */
internal fun copyDownload(
    input: InputStream,
    output: OutputStream,
    onProgress: (Long) -> Unit,
    checkCancelled: () -> Unit,
): Long {
    val buffer = ByteArray(32 * 1024)
    var bytesCopied = 0L
    var lastProgressNanos = System.nanoTime()
    onProgress(0)
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) throw IOException("The remote file read made no progress.")
        checkCancelled()
        output.write(buffer, 0, count)
        bytesCopied += count
        val nowNanos = System.nanoTime()
        if (nowNanos - lastProgressNanos >= 100_000_000) {
            onProgress(bytesCopied)
            lastProgressNanos = nowNanos
        }
    }
    checkCancelled()
    output.flush()
    onProgress(bytesCopied)
    return bytesCopied
}
