package com.codex.remote.data.ssh

import com.codex.remote.domain.RemotePlatform
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCommandsTest {
    @Test
    fun defaultPrefixRunsCodexWithEachRequiredArgumentList() {
        for ((command, arguments) in commands(RemotePlatform.POSIX, "codex", "/tmp/socket")) {
            // Decode the outer login-shell quoting, then run a stand-in shell function.
            assertTrue(command.startsWith("exec \"\${SHELL:-/bin/sh}\" -lc "))
            val decoder = ProcessBuilder("/bin/sh", "-c", command.replace("exec \"\${SHELL:-/bin/sh}\" -lc", "printf '%s'"))
                .start()
            val script = decoder.inputStream.bufferedReader().readText()
            assertEquals(0, decoder.waitFor())
            val process = ProcessBuilder("/bin/sh", "-c", "codex() { printf '%s\\n' \"\$@\"; }; $script").start()
            assertEquals(arguments, process.inputStream.bufferedReader().readLines())
            assertEquals(0, process.waitFor())
        }
    }

    @Test
    fun posixPrefixSupportsEnvironmentAssignmentsQuotedExecutableAndGlobalFlags() {
        val directory = Files.createTempDirectory("codex-command-test").toFile()
        try {
            val executable = directory.resolve("custom codex")
            executable.writeText("#!/bin/sh\nprintf '%s\\n' \"\$CODEX_HOME\" \"\$@\"\n")
            assertTrue(executable.setExecutable(true))
            val prefix = "CODEX_HOME='/tmp/custom home' '${executable.absolutePath}' -c 'model=\"custom model\"'"
            val socketPath = "/tmp/sp ace/'\"\$USER;`id`;\$(id).sock"
            for ((command, arguments) in commands(RemotePlatform.POSIX, prefix, socketPath)) {
                val builder = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true)
                builder.environment()["SHELL"] = "/bin/sh"
                val process = builder.start()
                try {
                    assertTrue("Command did not finish", process.waitFor(5, TimeUnit.SECONDS))
                    assertEquals(listOf("/tmp/custom home", "-c", "model=\"custom model\"") + arguments,
                        process.inputStream.bufferedReader().readLines())
                    assertEquals(0, process.exitValue())
                } finally {
                    process.destroyForcibly()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun powershellKeepsThePrefixAndQuotesAppendedArgumentsInAnEncodedScript() {
        val custom = "\$env:CODEX_HOME='C:\\Codex Home'; & 'C:\\Tools\\custom codex.exe' -c 'model=\"custom\"'"
        for (prefix in listOf("codex", custom)) {
            val commands = listOf(
                codexVersionCommand(RemotePlatform.WINDOWS, prefix) to "'--version'",
                appServerCommand(RemotePlatform.WINDOWS, prefix) to "'app-server' '--listen' 'stdio://'",
                daemonStartCommand(RemotePlatform.WINDOWS, prefix) to "'app-server' 'daemon' 'start'",
                daemonProxyCommand(RemotePlatform.WINDOWS, "C:\\a'b\\control.sock", prefix) to
                    "'app-server' 'proxy' '--sock' 'C:\\a''b\\control.sock'",
            )
            for ((command, arguments) in commands) {
                assertTrue(command.startsWith("powershell.exe -NoLogo -NonInteractive -EncodedCommand "))
                assertFalse(command.contains("-NoProfile"))
                val script = Base64.getDecoder().decode(command.substringAfterLast(' ')).toString(Charsets.UTF_16LE)
                assertEquals("$prefix $arguments; exit \$LASTEXITCODE", script)
            }
        }
    }

    @Test
    fun rejectsBlankAndNulPrefixesAndUnresolvedPlatforms() {
        for (prefix in listOf("", " \n\t", "codex\u0000")) {
            for (platform in listOf(RemotePlatform.POSIX, RemotePlatform.WINDOWS)) {
                assertThrows(IllegalArgumentException::class.java) { codexVersionCommand(platform, prefix) }
            }
        }
        assertThrows(IllegalStateException::class.java) { codexVersionCommand(RemotePlatform.AUTO) }
    }

    private fun commands(platform: RemotePlatform, prefix: String, socketPath: String) = listOf(
        codexVersionCommand(platform, prefix) to listOf("--version"),
        appServerCommand(platform, prefix) to listOf("app-server", "--listen", "stdio://"),
        daemonStartCommand(platform, prefix) to listOf("app-server", "daemon", "start"),
        daemonProxyCommand(platform, socketPath, prefix) to listOf("app-server", "proxy", "--sock", socketPath),
    )
}
