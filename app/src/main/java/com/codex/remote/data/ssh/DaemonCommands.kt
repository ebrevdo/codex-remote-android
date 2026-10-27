package com.codex.remote.data.ssh

import com.codex.remote.data.security.checkJsonDepth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    validateDaemonSocketPath(socketPath)
    val version = string("appServerVersion")?.takeIf { it.isNotBlank() && it.length <= 128 }
    return DaemonEndpoint(socketPath, version)
}

internal fun validateDaemonSocketPath(path: String) {
    require(path.isNotBlank() && path.length <= 4096 && path.none { it.isISOControl() }) {
        "Codex daemon returned an invalid socket path"
    }
}
