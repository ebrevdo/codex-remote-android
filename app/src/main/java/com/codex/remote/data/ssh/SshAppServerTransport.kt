package com.codex.remote.data.ssh

import android.content.Context
import com.codex.remote.domain.AppServerMode
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.ConnectionSecrets
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.SavedConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

class HostKeyChangedException(
    expected: String,
    actual: String,
) : SecurityException("SSH host key changed. Saved: $expected. Received: $actual")

class UnknownHostKeyException(val fingerprint: String) :
    SecurityException("Confirm the SSH host fingerprint before the first connection: $fingerprint")

class RemoteCodexUnavailableException(message: String) : IllegalStateException(message)

class ActiveSshTransport internal constructor(
    private val ssh: SSHClient,
    private val session: Session,
    private val command: Session.Command,
    val fingerprint: String,
    override val remotePlatform: RemotePlatform,
    override val codexVersion: String,
    private val messages: MessageStream,
) : AppServerTransport {
    override val errorReader: BufferedReader = BufferedReader(InputStreamReader(command.errorStream, Charsets.UTF_8))

    override fun readMessage(): String? = messages.readMessage()
    override fun writeMessage(message: String) = withIoDeadline(15_000, ::close) {
        messages.writeMessage(message)
    }

    override fun close() {
        // Close the socket first so blocked readers/writers cannot prevent shutdown.
        runCatching { ssh.close() }
        runCatching { messages.close() }
        runCatching { command.close() }
        runCatching { session.close() }
        runCatching { ssh.disconnect() }
        runCatching { ssh.close() }
    }
}

class SshAppServerTransportFactory(private val context: Context) {
    suspend fun open(
        connection: SavedConnection,
        secrets: ConnectionSecrets,
    ): ActiveSshTransport {
        var opened: ActiveSshTransport? = null
        try {
            return withContext(Dispatchers.IO) {
                openBlocking(connection, secrets).also { opened = it }
            }
        } catch (error: Throwable) {
            // withContext can discard a successfully opened transport on cancellation.
            opened?.close()
            throw error
        }
    }

    private fun openBlocking(connection: SavedConnection, secrets: ConnectionSecrets): ActiveSshTransport {
        // Remove key files left by older versions after a process crash.
        context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("codex_remote_") && file.name.endsWith(".key")
        }?.forEach { file ->
            check(file.delete()) { "Unable to remove a legacy SSH key cache file" }
        }
        var observedFingerprint = ""
        val ssh = authenticatedClient(connection, secrets) { observedFingerprint = it }
        return try {
            val remotePlatform = resolvePlatform(ssh, connection.platform)
            val codexVersion = readCodexVersion(ssh, remotePlatform)
            openAppServerTransport(ssh, observedFingerprint, remotePlatform, codexVersion, connection.appServerMode)
        } catch (error: Throwable) {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
            throw error
        }
    }

    private fun authenticatedClient(
        connection: SavedConnection,
        secrets: ConnectionSecrets,
        onFingerprint: (String) -> Unit,
    ): SSHClient {
        val ssh = SSHClient(androidCompatibleSshConfig())
        var unknownFingerprint: String? = null
        var changedFingerprint: String? = null
        ssh.connectTimeout = 15_000
        ssh.timeout = 30_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val actual = sha256Fingerprint(key)
                onFingerprint(actual)
                val expected = connection.hostKeyFingerprint
                if (expected.isBlank()) {
                    unknownFingerprint = actual
                    return false
                }
                if (expected != actual) {
                    changedFingerprint = actual
                    return false
                }
                return true
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })

        try {
            ssh.connect(connection.host, connection.port)
            when (connection.authType) {
                AuthType.PASSWORD -> ssh.authPassword(connection.username, secrets.password)
                AuthType.PRIVATE_KEY -> authenticatePrivateKey(ssh, connection.username, secrets)
            }
            return ssh
        } catch (error: Throwable) {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
            unknownFingerprint?.let { throw UnknownHostKeyException(it) }
            changedFingerprint?.let { throw HostKeyChangedException(connection.hostKeyFingerprint, it) }
            throw error
        }
    }

    private fun authenticatePrivateKey(
        ssh: SSHClient,
        username: String,
        secrets: ConnectionSecrets,
    ) {
        val passphrase = secrets.passphrase.takeIf { it.isNotBlank() }?.toCharArray()
        try {
            val provider = ssh.loadKeys(secrets.privateKey, null, PasswordUtils.createOneOff(passphrase))
            ssh.authPublickey(username, provider)
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    private fun sha256Fingerprint(key: PublicKey): String {
        val wireKey = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(wireKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun resolvePlatform(ssh: SSHClient, configured: RemotePlatform): RemotePlatform {
        if (configured != RemotePlatform.AUTO) return configured
        val probe = runSshCommand(ssh, "printf '__CODEX_POSIX__'")
        return if (probe.exitStatus == 0 && probe.stdout.contains("__CODEX_POSIX__")) {
            RemotePlatform.POSIX
        } else {
            RemotePlatform.WINDOWS
        }
    }

    private fun readCodexVersion(ssh: SSHClient, platform: RemotePlatform): String {
        val probe = runSshCommand(ssh, codexVersionCommand(platform))
        val version = probe.stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("codex-cli ") || it.startsWith("codex ") }
        if (probe.exitStatus != 0 || version == null) {
            val detail = probe.stderr.lineSequence().lastOrNull { it.isNotBlank() }
                ?: probe.stdout.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "codex --version returned no version"
            throw RemoteCodexUnavailableException(
                "The remote login shell could not find a working Codex CLI. Install Codex on the remote host and check it with codex --version. $detail",
            )
        }
        return version.substringAfter(' ').trim()
    }

}

internal fun codexVersionCommand(platform: RemotePlatform): String = when (platform) {
    RemotePlatform.AUTO -> error("AUTO platform must be resolved before building a Codex command")
    RemotePlatform.POSIX -> "exec \"\${SHELL:-/bin/sh}\" -lc 'codex --version'"
    RemotePlatform.WINDOWS ->
        "powershell.exe -NoLogo -NonInteractive -Command \"& { codex --version }\""
}

internal fun appServerCommand(platform: RemotePlatform): String = when (platform) {
    RemotePlatform.AUTO -> error("AUTO platform must be resolved before building a Codex command")
    RemotePlatform.POSIX ->
        "exec \"\${SHELL:-/bin/sh}\" -lc 'exec codex app-server --listen stdio://'"
    RemotePlatform.WINDOWS ->
        "powershell.exe -NoLogo -NonInteractive -Command \"& { codex app-server --listen stdio:// }\""
}


/** Also used by the JVM integration test with a real, authenticated SSH connection. */
internal fun openAppServerTransport(
    ssh: SSHClient,
    fingerprint: String,
    platform: RemotePlatform,
    cliVersion: String,
    mode: AppServerMode,
): ActiveSshTransport {
    val endpoint = if (mode == AppServerMode.DAEMON) {
        val result = runSshCommand(ssh, daemonStartCommand(platform), timeoutSeconds = 60)
        if (result.exitStatus != 0) {
            throw RemoteCodexUnavailableException(
                "Unable to start the Codex daemon. This mode requires daemon and proxy support. " +
                    result.stderr.trim().takeLast(2048),
            )
        }
        parseDaemonStart(result.stdout)
    } else null
    ssh.timeout = 0
    val session = ssh.startSession()
    try {
        val command = session.exec(endpoint?.let { daemonProxyCommand(platform, it.socketPath) } ?: appServerCommand(platform))
        val messages = if (endpoint == null) {
            JsonLineMessageStream(command.inputStream, command.outputStream)
        } else {
            WebSocketMessageStream(command.inputStream, command.outputStream, { ssh.close() }).apply { connect() }
        }
        return ActiveSshTransport(ssh, session, command, fingerprint, platform, endpoint?.version ?: cliVersion, messages)
    } catch (error: Throwable) {
        runCatching { ssh.close() }
        runCatching { session.close() }
        throw error
    }
}
