package com.codex.remote.data.ssh

import com.codex.remote.data.security.checkJsonDepth
import com.codex.remote.domain.RemotePlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

internal data class DaemonEndpoint(val socketPath: String, val version: String?)

internal fun parseDaemonStart(output: String): DaemonEndpoint {
    checkJsonDepth(output)
    val response = Json.parseToJsonElement(output) as? JsonObject
        ?: throw RemoteCodexUnavailableException("Codex daemon start returned invalid JSON")
    fun string(name: String): String? = (response[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (string("status") !in setOf("started", "alreadyRunning")) {
        throw RemoteCodexUnavailableException("Codex daemon did not report a running app server")
    }
    val socketPath = string("socketPath")
        ?: throw RemoteCodexUnavailableException("Codex daemon did not return its socket path")
    validateSocketPath(socketPath)
    val version = string("appServerVersion")?.takeIf { it.isNotBlank() && it.length <= 128 }
    return DaemonEndpoint(socketPath, version)
}

internal fun daemonStartCommand(platform: RemotePlatform): String = loginShellCommand(
    platform,
    "codex app-server daemon start",
    "codex app-server daemon start; exit \$LASTEXITCODE",
)

internal fun daemonProxyCommand(platform: RemotePlatform, socketPath: String): String {
    validateSocketPath(socketPath)
    return loginShellCommand(
        platform,
        "exec codex app-server proxy --sock ${shellQuote(socketPath)}",
        "codex app-server proxy --sock '${socketPath.replace("'", "''")}'; exit \$LASTEXITCODE",
    )
}

private fun validateSocketPath(path: String) {
    require(path.isNotBlank() && path.length <= 4096 && path.none { it.isISOControl() }) {
        "Codex daemon returned an invalid socket path"
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private fun loginShellCommand(platform: RemotePlatform, posix: String, windows: String): String = when (platform) {
    RemotePlatform.AUTO -> error("Resolve the remote platform before running Codex")
    RemotePlatform.POSIX -> "exec \"\${SHELL:-/bin/sh}\" -lc ${shellQuote(posix)}"
    RemotePlatform.WINDOWS -> "powershell.exe -NoLogo -NonInteractive -EncodedCommand " +
        Base64.getEncoder().encodeToString(windows.toByteArray(Charsets.UTF_16LE))
}
