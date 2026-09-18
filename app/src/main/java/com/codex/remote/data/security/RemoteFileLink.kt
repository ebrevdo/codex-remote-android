package com.codex.remote.data.security

import java.net.URI
import java.net.URLDecoder

internal data class RemoteFileLink(val path: String, val line: Int? = null) {
    val directory: String get() = path.substringBeforeLast('/') + "/"
    val isMarkdown: Boolean get() = path.substringAfterLast('.').lowercase() in setOf("md", "markdown", "mdown")
}

private const val MAX_FILE_LINK_CHARS = 8192

private val windowsPath = Regex("^[A-Za-z]:/")
private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val lineSuffix = Regex(":([0-9]+)(?::[0-9]+)?$")
private val lineFragment = Regex("L([0-9]+)(?:C[0-9]+)?(?:-L?[0-9]+)?")

/** Resolve file references on the SSH host; browser URLs never become file reads. */
internal fun parseRemoteFileLink(raw: String, baseDirectory: String): RemoteFileLink? {
    if (raw.length !in 1..MAX_FILE_LINK_CHARS || raw.any(Char::isISOControl)) return null
    var target = raw.replace('\\', '/')
    val scheme = uriScheme.find(target)
    if (target.startsWith("file:", ignoreCase = true)) {
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        if (uri.isOpaque || !uri.rawAuthority.isNullOrEmpty() || uri.rawQuery != null) return null
        target = uri.rawPath ?: return null
        uri.rawFragment?.let { target += "#$it" }
    } else if (scheme != null && !windowsPath.containsMatchIn(target)) {
        // README.md:20 is a source citation even though URI treats README.md as a scheme.
        val suffix = lineSuffix.find(target.substringBefore('#'))
        if ('.' !in scheme.value || suffix?.range?.first != scheme.range.last) return null
    }
    if (target.startsWith("//") || target.startsWith('#') || '?' in target || target.startsWith("~/")) return null
    val fragment = target.substringAfter('#', "")
    target = target.substringBefore('#')
    val suffix = lineSuffix.find(target)
    val lineText = lineFragment.matchEntire(fragment)?.groupValues?.get(1) ?: suffix?.groupValues?.get(1)
    val line = lineText?.let { it.toIntOrNull()?.takeIf { number -> number > 0 } ?: return null }
    if (suffix != null) target = target.substring(0, suffix.range.first)
    target = runCatching { URLDecoder.decode(target.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
    if (target.isBlank() || target.any(Char::isISOControl) || '\\' in target || target.startsWith("//")) return null
    // file:///C:/... is the URI spelling of an absolute Windows path.
    if (target.startsWith('/') && windowsPath.containsMatchIn(target.drop(1))) target = target.drop(1)
    val absolute = when {
        target.startsWith('/') || windowsPath.containsMatchIn(target) -> target
        else -> {
            if (baseDirectory.length > MAX_FILE_LINK_CHARS || baseDirectory.any(Char::isISOControl)) return null
            val base = baseDirectory.replace('\\', '/')
            if (base.startsWith("//")) return null
            if (!base.startsWith('/') && !windowsPath.containsMatchIn(base)) return null
            "$base/$target"
        }
    }
    val prefix = if (windowsPath.containsMatchIn(absolute)) absolute.take(3) else "/"
    val parts = mutableListOf<String>()
    for (part in absolute.removePrefix(prefix).split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
    }
    if (parts.isEmpty()) return null
    val path = prefix + parts.joinToString("/")
    if (path.length > MAX_FILE_LINK_CHARS) return null
    return RemoteFileLink(path, line)
}
