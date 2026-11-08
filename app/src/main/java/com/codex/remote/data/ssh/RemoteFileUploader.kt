package com.codex.remote.data.ssh

import com.codex.remote.domain.RemotePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.SessionFactory
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPEngine
import java.io.InputStream
import java.util.EnumSet
import java.util.UUID

/** Stream arbitrary bytes into a fresh private directory without changing project files. */
internal suspend fun uploadRemoteFile(
    ssh: SSHClient,
    platform: RemotePlatform,
    displayName: String,
    input: InputStream,
    onProgress: (Long) -> Unit,
): String {
    val context = currentCoroutineContext()
    return runInterruptible(Dispatchers.IO) {
        ssh.startSession().use { session ->
            val factory = object : SessionFactory by ssh {
                override fun startSession() = session
            }
            SFTPEngine(factory).use { sftp ->
                sftp.timeoutMs = 15_000
                sftp.init()
                val home = sftp.canonicalize(".").trimEnd('/')
                val directory = "$home/.codex-remote-upload-${UUID.randomUUID()}"
                val path = "$directory/${uploadFileName(displayName)}"
                context.ensureActive()
                sftp.makeDir(directory, FileAttributes.Builder().withPermissions(0b111_000_000).build())
                var created = false
                var completed = false
                try {
                    sftp.open(
                        path,
                        EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
                        FileAttributes.Builder().withPermissions(0b110_000_000).build(),
                    ).use { file ->
                        created = true
                        file.RemoteFileOutputStream(0, 8).use { output ->
                            copyFileWithProgress(input, output, onProgress) { context.ensureActive() }
                        }
                    }
                    context.ensureActive()
                    completed = true
                    uploadedPathForPlatform(path, platform)
                } finally {
                    if (!completed) {
                        // Clear the interrupted flag while making best-effort cleanup requests.
                        val interrupted = Thread.interrupted()
                        try {
                            if (created) runCatching { sftp.remove(path) }
                            runCatching { sftp.removeDir(directory) }
                        } finally {
                            if (interrupted) Thread.currentThread().interrupt()
                        }
                    }
                }
            }
        }
    }
}

internal fun uploadFileName(displayName: String): String {
    val name = displayName.replace('\\', '/').substringAfterLast('/')
        .map { if (it.isISOControl() || it in ":*?\"<>|") '_' else it }.joinToString("")
        .trim(' ', '.')
        .ifEmpty { "file" }
    // At most 80 UTF-16 units fit within common 255-byte filename limits, including UTF-8.
    val shortened = if (name.length > 80) name.take(60) + name.takeLast(20) else name
    val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\.|$)", RegexOption.IGNORE_CASE)
    return if (reserved.containsMatchIn(shortened)) "_$shortened" else shortened
}

internal fun uploadedPathForPlatform(path: String, platform: RemotePlatform): String =
    if (platform == RemotePlatform.WINDOWS && Regex("^/[A-Za-z]:/").containsMatchIn(path)) path.drop(1) else path
