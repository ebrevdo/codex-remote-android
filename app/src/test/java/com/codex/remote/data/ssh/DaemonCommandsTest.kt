package com.codex.remote.data.ssh

import com.codex.remote.domain.AppServerMode
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.SavedConnection
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DaemonCommandsTest {
    @Test
    fun acceptsStartedAndReusedDaemons() {
        for (status in listOf("started", "alreadyRunning")) {
            assertEquals(DaemonEndpoint("/tmp/control.sock", "0.154.0"), parseDaemonStart("""{"status":"$status","socketPath":"/tmp/control.sock","appServerVersion":"0.154.0","extra":true}"""))
        }
    }

    @Test
    fun failsClosedForInvalidLifecycleResponses() {
        for (response in listOf(
            "{}", "[]", "null", "not JSON",
            """{"status":"notRunning","socketPath":"/tmp/sock"}""",
            """{"status":"started","socketPath":42}""",
            """{"status":"started","socketPath":""}""",
            """{"status":"started","socketPath":"/tmp/\u0000sock"}""",
            """{"status":"started","socketPath":"/tmp/\nsock"}""",
            """{"status":"started","socketPath":"/tmp/sock"} {"status":"started"}""",
        )) assertThrows(response, Exception::class.java) { parseDaemonStart(response) }
    }

    @Test
    fun posixProxyTreatsMetacharactersAsOneLiteralArgument() {
        val path = "/tmp/sp ace/'\"\$USER;`id`;\$(id).sock"
        // Execute both shell quoting layers, substituting a printf-only codex function.
        val command = daemonProxyCommand(RemotePlatform.POSIX, path)
        val inner = ProcessBuilder("/bin/sh", "-c", command.replace("exec \"\${SHELL:-/bin/sh}\" -lc", "printf '%s'"))
            .start().inputStream.bufferedReader().readText()
        val script = "codex() { printf '%s\\n' \"\$#\" \"\$@\"; }; " + inner.removePrefix("exec ")
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        assertEquals(listOf("4", "app-server", "proxy", "--sock", path), process.inputStream.bufferedReader().readLines())
        assertEquals(0, process.waitFor())
    }

    @Test
    fun powershellProxyUsesEncodedScriptAndEscapesApostrophes() {
        val command = daemonProxyCommand(RemotePlatform.WINDOWS, "C:\\a'b\\control.sock")
        assertTrue(command.startsWith("powershell.exe -NoLogo -NonInteractive -EncodedCommand "))
        assertEquals("codex app-server proxy --sock 'C:\\a''b\\control.sock'; exit \$LASTEXITCODE", Base64.getDecoder().decode(command.substringAfterLast(' ')).toString(Charsets.UTF_16LE))
    }

    @Test
    fun savedHostsKeepSessionModeAndDaemonModeRoundTrips() {
        val old = """{"name":"host","host":"host","username":"user","authType":"PASSWORD"}"""
        assertEquals(AppServerMode.SESSION, Json.decodeFromString(SavedConnection.serializer(), old).appServerMode)
        val connection = SavedConnection(name = "host", host = "host", username = "user", authType = AuthType.PASSWORD, appServerMode = AppServerMode.DAEMON)
        assertEquals(connection, Json.decodeFromString(SavedConnection.serializer(), Json.encodeToString(SavedConnection.serializer(), connection)))
    }
}
