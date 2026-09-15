package com.codex.remote.data.security

import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.ApprovalRequest
import kotlinx.serialization.json.*

internal const val MAX_APPROVAL_CHARS = 64 * 1024

private val reviewJson = Json { prettyPrint = true }

internal data class ApprovalReview(val details: String, val blockedReason: String? = null) {
    val canApprove: Boolean get() = blockedReason == null
}

/** Validate the same payload that is shown and later returned to the server. */
internal fun reviewApproval(request: ApprovalRequest): ApprovalReview {
    fun blocked(reason: String) = ApprovalReview("This request cannot be approved.", reason)
    if (request.rawParams.length > MAX_APPROVAL_CHARS ||
        (request.rawFileChanges?.length ?: 0) > MAX_APPROVAL_CHARS
    ) return blocked("The request exceeds the review size limit. Review it on the trusted remote host.")
    val params = runCatching {
        checkJsonDepth(request.rawParams, 32)
        Json.parseToJsonElement(request.rawParams) as JsonObject
    }.getOrElse { return blocked("The remote request is malformed.") }
    val changes = request.rawFileChanges?.let { raw ->
        runCatching {
            checkJsonDepth(raw, 32)
            Json.parseToJsonElement(raw) as JsonArray
        }.getOrElse { return blocked("The file-change details are malformed.") }
    }
    val supported = when (request.kind) {
        ApprovalKind.COMMAND -> validCommandApproval(request.rawMethod, params)
        ApprovalKind.FILE_CHANGE -> validFileChangeApproval(request.rawMethod, params, changes)
        ApprovalKind.PERMISSION -> request.rawMethod == "item/permissions/requestApproval" &&
            params.keys.all { it in permissionFields } && params.text("cwd") != null &&
            validPermissions(params["permissions"])
        ApprovalKind.USER_INPUT -> request.rawMethod == "item/tool/requestUserInput" &&
            request.questions.isNotEmpty() && request.questions.size <= 20 &&
            (params["questions"] as? JsonArray)?.size == request.questions.size &&
            request.questions.map { it.id }.distinct().size == request.questions.size
        ApprovalKind.UNKNOWN -> false
    }
    val explanation = when (request.kind) {
        ApprovalKind.PERMISSION -> "Access lasts for this turn only. Network enabled means unrestricted network access."
        ApprovalKind.FILE_CHANGE -> "Review every path and diff below. Persistent write grants are not supported."
        ApprovalKind.COMMAND -> "Review the exact command, working directory, and any network destination below."
        ApprovalKind.USER_INPUT -> "Only the answers you submit will be sent."
        ApprovalKind.UNKNOWN -> "Unsupported remote request."
    }
    val details = buildString {
        appendLine(explanation)
        if (changes != null) {
            appendLine()
            appendLine("Complete file changes, including rename destinations:")
            appendLine(reviewJson.encodeToString(JsonArray.serializer(), changes))
        }
        appendLine()
        append(reviewJson.encodeToString(JsonObject.serializer(), params))
    }
    return ApprovalReview(
        visibleControls(details),
        if (supported) null else "Missing details or an unsupported request or permission scope. Deny it and review it on the trusted remote host.",
    )
}

internal fun approvalResult(request: ApprovalRequest, decision: String, answers: Map<String, List<String>>): JsonObject {
    require(decision == "accept" || decision == "decline") { "Unsupported approval decision" }
    val accepted = decision == "accept"
    require(!accepted || reviewApproval(request).canApprove) { "This request cannot be safely approved" }
    return when (request.kind) {
        ApprovalKind.PERMISSION -> buildJsonObject {
            val params = if (accepted) Json.parseToJsonElement(request.rawParams).jsonObject else null
            val permissions = params?.get("permissions") as? JsonObject
            put("permissions", JsonObject(permissions?.filterValues { it != JsonNull }.orEmpty()))
            put("scope", "turn")
        }
        ApprovalKind.USER_INPUT -> buildJsonObject {
            put("answers", buildJsonObject {
                if (accepted) request.questions.forEach { question ->
                    val values = answers[question.id].orEmpty()
                    require(values.sumOf { it.length.toLong() } <= MAX_APPROVAL_CHARS) { "Answer is too long" }
                    if (values.isNotEmpty()) put(question.id, buildJsonObject {
                        put("answers", JsonArray(values.map(::JsonPrimitive)))
                    })
                }
            })
        }
        else -> buildJsonObject {
            val legacy = request.rawMethod in setOf("execCommandApproval", "applyPatchApproval")
            val responseDecision = when {
                !legacy -> decision
                accepted -> "approved"
                else -> "denied"
            }
            put("decision", responseDecision)
        }
    }
}

private fun validCommandApproval(method: String, params: JsonObject): Boolean {
    if (method !in setOf("item/commandExecution/requestApproval", "execCommandApproval") ||
        params.keys.any { it !in commandFields }
    ) return false

    val command = params["command"]
    val networkContext = params["networkApprovalContext"]
    if (command.isAbsent()) {
        return method == "item/commandExecution/requestApproval" && validNetworkContext(networkContext)
    }
    return validCommand(command) && params.text("cwd") != null &&
        (networkContext.isAbsent() || validNetworkContext(networkContext))
}

private fun validFileChangeApproval(method: String, params: JsonObject, changes: JsonArray?): Boolean {
    if (method !in setOf("item/fileChange/requestApproval", "applyPatchApproval") ||
        params.keys.any { it !in fileFields } || !params["grantRoot"].isAbsent()
    ) return false

    if (method == "applyPatchApproval") return validLegacyChanges(params["fileChanges"])
    return params.text("threadId") != null && params.text("turnId") != null && params.text("itemId") != null &&
        changes != null && validModernChanges(changes)
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private fun JsonElement?.isAbsent() = this == null || this == JsonNull
private fun validCommand(value: JsonElement?): Boolean =
    (value is JsonPrimitive && value.isString && value.content.isNotBlank()) ||
        (value is JsonArray && value.isNotEmpty() && value.all { it is JsonPrimitive && it.isString } &&
            (value.first() as JsonPrimitive).content.isNotBlank())

private fun validNetworkContext(value: JsonElement?): Boolean {
    val obj = value as? JsonObject ?: return false
    return obj.keys.all { it in setOf("host", "protocol") } && obj.text("host") != null &&
        obj.text("protocol") in setOf("http", "https", "socks5Tcp", "socks5Udp")
}

private fun validPermissions(value: JsonElement?): Boolean {
    val obj = value as? JsonObject ?: return false
    if (obj.isEmpty() || obj.keys.any { it !in setOf("network", "fileSystem") }) return false
    val network = obj["network"]
    if (!network.isAbsent()) {
        val fields = network as? JsonObject ?: return false
        if (fields.keys.any { it != "enabled" }) return false
        val enabled = fields["enabled"]
        if (!enabled.isAbsent() && (enabled !is JsonPrimitive || enabled.isString || enabled.booleanOrNull == null)) return false
    }
    val fileSystem = obj["fileSystem"]
    if (!fileSystem.isAbsent()) {
        val fields = fileSystem as? JsonObject ?: return false
        if (fields.keys.any { it !in setOf("read", "write", "entries", "globScanMaxDepth") }) return false
        for (key in listOf("read", "write")) {
            val paths = fields[key]
            if (!paths.isAbsent() && (paths !is JsonArray || paths.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() })) return false
        }
        val depth = fields["globScanMaxDepth"]
        if (!depth.isAbsent() && (depth !is JsonPrimitive || depth.isString || depth.intOrNull == null || depth.int < 0)) return false
        val entries = fields["entries"]
        if (!entries.isAbsent() && (entries !is JsonArray || entries.any { !validEntry(it) })) return false
    }
    return true
}

private fun validEntry(value: JsonElement): Boolean {
    val obj = value as? JsonObject ?: return false
    if (obj.keys != setOf("path", "access") || obj.text("access") !in setOf("read", "write", "deny")) return false
    val path = obj["path"] as? JsonObject ?: return false
    return when (path.text("type")) {
        "path" -> path.keys == setOf("type", "path") && path.text("path") != null
        "glob_pattern" -> path.keys == setOf("type", "pattern") && path.text("pattern") != null
        "special" -> {
            val special = path["value"] as? JsonObject ?: return false
            path.keys == setOf("type", "value") && when (special.text("kind")) {
                "root", "minimal", "tmpdir", "slash_tmp" -> special.keys == setOf("kind")
                "project_roots" -> special.keys.all { it in setOf("kind", "subpath") } &&
                    (special["subpath"].isAbsent() || special.text("subpath") != null)
                else -> false
            }
        }
        else -> false
    }
}

private fun validModernChanges(changes: JsonArray): Boolean = changes.isNotEmpty() && changes.all { change ->
    val obj = change as? JsonObject ?: return@all false
    if (obj.keys != setOf("path", "kind", "diff") || obj.text("path") == null ||
        (obj["diff"] as? JsonPrimitive)?.isString != true) return@all false
    val kind = obj["kind"] as? JsonObject ?: return@all false
    when (kind.text("type")) {
        "add", "delete" -> kind.keys == setOf("type")
        "update" -> kind.keys.all { it in setOf("type", "move_path") } && obj.text("diff") != null &&
            (kind["move_path"].isAbsent() || kind.text("move_path") != null)
        else -> false
    }
}

private fun validLegacyChanges(value: JsonElement?): Boolean {
    val changes = value as? JsonObject ?: return false
    return changes.isNotEmpty() && changes.all { (path, change) ->
        val obj = change as? JsonObject ?: return@all false
        path.isNotBlank() && when (obj.text("type")) {
            "add", "delete" -> obj.keys.all { it in setOf("type", "content") } &&
                (obj["content"] as? JsonPrimitive)?.isString == true
            "update" -> obj.keys.all { it in setOf("type", "unified_diff", "move_path") } &&
                obj.text("unified_diff") != null && (obj["move_path"].isAbsent() || obj.text("move_path") != null)
            else -> false
        }
    }
}

// Make terminal and directional controls visible in security-sensitive review text.
internal fun visibleControls(value: String): String = buildString {
    value.forEach { char ->
        if ((char.isISOControl() && char != '\n' && char != '\t') ||
            char in '\u202a'..'\u202e' || char in '\u2066'..'\u2069' || char == '\u200e' || char == '\u200f'
        ) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
    }
}

private val commandFields = setOf(
    "threadId", "turnId", "itemId", "startedAtMs", "approvalId", "environmentId",
    "reason", "networkApprovalContext", "command", "cwd", "commandActions",
    "proposedExecpolicyAmendment", "proposedNetworkPolicyAmendments",
    "conversationId", "callId", "parsedCmd",
)
private val fileFields = setOf("threadId", "turnId", "itemId", "startedAtMs", "reason", "grantRoot", "conversationId", "callId", "fileChanges")
private val permissionFields = setOf("threadId", "turnId", "itemId", "environmentId", "startedAtMs", "cwd", "reason", "permissions")
