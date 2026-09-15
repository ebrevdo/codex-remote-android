package com.codex.remote.data.security

import org.junit.Assert.*
import org.junit.Test

class ExternalLinkPolicyTest {
    @Test fun permitsHttpsAndPreservesEncodedPath() {
        assertEquals("https://example.com/a%2Fb?q=1", validatedBrowserUrl("https://example.com/a%2Fb?q=1"))
        assertEquals("https://auth.openai.com/codex/device", validatedBrowserUrl("https://auth.openai.com/codex/device", true))
    }

    @Test fun rejectsAndroidIntentsAndMisleadingAuthorities() {
        listOf(
            "intent://example.com/#Intent;scheme=https;end",
            "javascript:alert(1)", "file:///etc/passwd", "content://example", "http://example.com",
            "https://auth.openai.com@evil.example/codex/device",
            "https://example.com:8443/path", "https://example.com./",
            "https://example.com\\@evil.example/", "https://example.com/\n",
            "https://ｅxample.com/", "https://%65xample.com/",
        ).forEach { assertNull(it, validatedBrowserUrl(it)) }
    }

    @Test fun deviceAuthenticationCannotUseArbitraryDestinations() {
        listOf(
            "https://auth.openai.com.evil.example/codex/device",
            "https://evil.example/codex/device",
            "https://auth.openai.com/codex/device?redirect=evil",
            "https://auth.openai.com/codex/device#evil",
            "https://auth.openai.com/codex/other",
        ).forEach { assertNull(it, validatedBrowserUrl(it, true)) }
    }
}
