package com.codex.remote.data.ssh

import com.codex.remote.domain.DEFAULT_CODEX_COMMAND_PREFIX
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.isValidCodexCommandPrefix
import java.util.Base64

internal fun codexVersionCommand(platform: RemotePlatform, commandPrefix: String = DEFAULT_CODEX_COMMAND_PREFIX): String =
    codexCommand(platform, commandPrefix, "--version")

internal fun appServerCommand(platform: RemotePlatform, commandPrefix: String = DEFAULT_CODEX_COMMAND_PREFIX): String =
    codexCommand(platform, commandPrefix, "app-server", "--listen", "stdio://")

internal fun daemonStartCommand(platform: RemotePlatform, commandPrefix: String = DEFAULT_CODEX_COMMAND_PREFIX): String =
    codexCommand(platform, commandPrefix, "app-server", "daemon", "start")

internal fun daemonProxyCommand(
    platform: RemotePlatform,
    socketPath: String,
    commandPrefix: String = DEFAULT_CODEX_COMMAND_PREFIX,
): String {
    validateDaemonSocketPath(socketPath)
    return codexCommand(platform, commandPrefix, "app-server", "proxy", "--sock", socketPath)
}

private fun codexCommand(platform: RemotePlatform, commandPrefix: String, vararg arguments: String): String {
    require(commandPrefix.isValidCodexCommandPrefix()) { "Enter a valid Codex command prefix" }
    val prefix = commandPrefix.trim()
    // The saved prefix is user-authored shell code; append app/server-provided arguments as literals.
    // Do not prepend exec: a POSIX prefix may begin with environment assignments.
    return when (platform) {
        RemotePlatform.AUTO -> error("Resolve the remote platform before running Codex")
        RemotePlatform.POSIX -> {
            val script = "$prefix ${arguments.joinToString(" ", transform = ::shellQuote)}"
            "exec \"\${SHELL:-/bin/sh}\" -lc ${shellQuote(script)}"
        }
        RemotePlatform.WINDOWS -> {
            val script = "$prefix ${arguments.joinToString(" ") { "'${it.replace("'", "''")}'" }}; exit \$LASTEXITCODE"
            "powershell.exe -NoLogo -NonInteractive -EncodedCommand " +
                Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        }
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
