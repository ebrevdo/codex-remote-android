package com.codex.remote.data.ssh

import com.codex.remote.data.security.readBytesBounded
import net.schmizz.sshj.SSHClient
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class SshCommandResult(val exitStatus: Int, val stdout: String, val stderr: String)

/** Drain both streams while waiting, so a noisy command cannot fill its SSH window. */
internal fun runSshCommand(ssh: SSHClient, commandLine: String, timeoutSeconds: Long = 15): SshCommandResult {
    val readers = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "ssh-command-output").apply { isDaemon = true }
    }
    val session = ssh.startSession()
    try {
        return withIoDeadline(timeoutSeconds * 1000, { ssh.close() }) {
            val command = session.exec(commandLine)
            try {
                fun read(stream: InputStream) = readers.submit<String> {
                    try {
                        stream.readBytesBounded(64 * 1024).toString(Charsets.UTF_8)
                    } catch (error: Exception) {
                        runCatching { ssh.close() }
                        throw error
                    }
                }
                val stdout = read(command.inputStream)
                val stderr = read(command.errorStream)
                command.join(timeoutSeconds, TimeUnit.SECONDS)
                if (command.isOpen) throw RemoteCodexUnavailableException("Remote Codex command timed out")
                SshCommandResult(command.exitStatus ?: -1, stdout.get(), stderr.get())
            } finally {
                runCatching { command.close() }
            }
        }
    } finally {
        runCatching { session.close() }
        readers.shutdownNow()
    }
}
