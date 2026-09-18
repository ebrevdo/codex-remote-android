package com.codex.remote.data.ssh

import com.codex.remote.data.rpc.CodexRpcClient
import com.codex.remote.domain.AppServerMode
import com.codex.remote.domain.RemotePlatform
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in test: tools/test_daemon_transport.py supplies a private loopback OpenSSH fixture. */
class DaemonSshIntegrationTest {
    @Test
    fun initializesOverSshProxyAndReusesDaemonAfterDisconnect() = runBlocking {
        val fixturePath = System.getenv("CODEX_REMOTE_SSH_TEST_FIXTURE")
        assumeTrue("Run tools/test_daemon_transport.py for the real Codex/OpenSSH test", fixturePath != null)
        val fixture = Json.parseToJsonElement(File(fixturePath!!).readText()).jsonObject
        fun value(key: String) = fixture.getValue(key).jsonPrimitive.content
        fun connect(): SSHClient = SSHClient(androidCompatibleSshConfig()).apply {
            addHostKeyVerifier(value("fingerprint"))
            connectTimeout = 5_000
            timeout = 10_000
            connect("127.0.0.1", value("port").toInt())
            authPublickey(value("username"), loadKeys(value("privateKey")))
        }
        var daemonPid: String? = null
        // Two independent proxy connections, then the original stdio transport.
        for (mode in listOf(AppServerMode.DAEMON, AppServerMode.DAEMON, AppServerMode.SESSION)) {
            connect().use { ssh ->
                val started = runSshCommand(ssh, daemonStartCommand(RemotePlatform.POSIX), 60)
                assertEquals(started.stderr, 0, started.exitStatus)
                val lifecycle = Json.parseToJsonElement(started.stdout).jsonObject
                val pid = lifecycle["pid"]?.jsonPrimitive?.content
                if (daemonPid == null) {
                    daemonPid = requireNotNull(pid)
                } else {
                    if (pid != null) assertEquals("A disconnect must leave the same daemon running", daemonPid, pid)
                    assertTrue(File("/proc/${requireNotNull(daemonPid).toLong()}").isDirectory)
                    assertEquals("alreadyRunning", lifecycle.getValue("status").jsonPrimitive.content)
                }
                val transport = openAppServerTransport(ssh, value("fingerprint"), RemotePlatform.POSIX, "integration", mode)
                CodexRpcClient(transport).use { client ->
                    withTimeout(20_000) {
                        val info = client.initialize()
                        assertTrue(info.userAgent.isNotBlank())
                        assertEquals(value("codexHome"), info.codexHome)
                        assertEquals("# SFTP preview\n\n**Works** without executing a shell.\n", client.readRemoteFile(value("previewFile")))
                        for (name in listOf("large.txt", "binary.bin", "pipe", "missing.txt", ".")) {
                            val failure = runCatching { client.readRemoteFile("${value("previewDirectory")}/$name") }.exceptionOrNull()
                            assertTrue("Reject $name", failure is java.io.IOException)
                        }
                        client.checkHealth()
                        assertTrue("Only the isolated empty history may be visible", client.listThreads().isEmpty())
                        println("Real Codex initialize + thread/list succeeded using $mode")
                    }
                }
            }
        }
        connect().use { ssh ->
            val response = runSshCommand(ssh, daemonStartCommand(RemotePlatform.POSIX), 60)
            assertEquals(0, response.exitStatus)
            assertEquals("alreadyRunning", Json.parseToJsonElement(response.stdout).jsonObject.getValue("status").jsonPrimitive.content)
            assertTrue(File("/proc/${requireNotNull(daemonPid).toLong()}").isDirectory)
        }
    }
}
