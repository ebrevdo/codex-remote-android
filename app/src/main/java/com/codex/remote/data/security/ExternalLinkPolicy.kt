package com.codex.remote.data.security

import java.net.URI
import java.util.Locale

/** Only browser HTTPS navigation is allowed. Android intent and custom schemes are rejected. */
internal fun validatedBrowserUrl(raw: String, deviceLogin: Boolean = false): String? {
    if (raw.length !in 1..8192 || raw.any { it.isWhitespace() || it.isISOControl() || it == '\\' } ||
        raw.any { it.code > 127 }) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque ||
        uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
        host.endsWith(".") || '%' in uri.rawAuthority || uri.rawFragment != null && deviceLogin
    ) return null
    if (deviceLogin && (host != "auth.openai.com" || uri.rawPath != "/codex/device" || uri.rawQuery != null)) return null
    // Do not reconstruct from decoded URI components: encoded slashes may be significant.
    return uri.toASCIIString()
}
