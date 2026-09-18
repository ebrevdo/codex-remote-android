package com.codex.remote.data.ssh

import com.codex.remote.data.security.readBytesBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.SessionFactory
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPEngine
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal const val MAX_REMOTE_FILE_BYTES = 256 * 1024

/** A separate SFTP channel shares SSH authentication without disturbing app-server I/O. */
internal suspend fun readRemoteTextFile(ssh: SSHClient, path: String): String = runInterruptible(Dispatchers.IO) {
    ssh.startSession().use { session ->
        withIoDeadline(15_000, { session.close() }) {
            val factory = object : SessionFactory by ssh {
                override fun startSession() = session
            }
            SFTPEngine(factory).use { sftp ->
                sftp.timeoutMs = 5_000
                sftp.init()
                checkPreviewFile(sftp.stat(path))
                sftp.open(path).use { file ->
                    checkPreviewFile(file.fetchAttributes())
                    decodeRemoteText(file.RemoteFileInputStream().readBytesBounded(MAX_REMOTE_FILE_BYTES))
                }
            }
        }
    }
}

private fun checkPreviewFile(attributes: FileAttributes) {
    if (attributes.type != FileMode.Type.REGULAR) throw IOException("Only regular text files can be previewed.")
    if (attributes.size > MAX_REMOTE_FILE_BYTES) throw IOException("File is too large to preview (256 KiB limit).")
}

internal fun decodeRemoteText(bytes: ByteArray): String {
    if (bytes.size > MAX_REMOTE_FILE_BYTES) throw IOException("File is too large to preview (256 KiB limit).")
    val text = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) {
        throw IOException("This file is not UTF-8 text.")
    }
    if (text.any { it.isISOControl() && it !in "\n\r\t" }) {
        throw IOException("Binary files cannot be previewed.")
    }
    return text
}
