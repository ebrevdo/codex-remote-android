package com.codex.remote.data.security

import org.junit.Assert.*
import org.junit.Test

class RemoteFileTypeTest {
    @Test
    fun filenamesCannotEscapeTheDeviceDownloadFolder() {
        assertEquals("report.md", remoteFileName("/repo/report.md"))
        assertEquals("chart.SVG", remoteFileName("C:\\repo\\chart.SVG"))
        assertEquals("secret.txt", remoteFileName("../../secret.txt"))
        for (path in listOf("", ".", "..", "/repo/..", "/repo/")) assertEquals("download", remoteFileName(path))
        assertEquals("a_b_.txt", remoteFileName("/repo/a\u0000b\n.txt"))
    }

    @Test
    fun imageReferencesSelectAnImagePreviewWithoutIncludingTheLineSuffix() {
        val file = checkNotNull(parseRemoteFileLink("/repo/chart.SVG:20", "/repo"))
        assertEquals("image/svg+xml", remoteImageMimeType(file.path))
        assertEquals("image/png", remoteImageMimeType("/repo/image.PnG"))
        assertNull(remoteImageMimeType("/repo/svg.txt"))
        assertNull(remoteImageMimeType("/repo/image.svg.html"))
        assertNull(remoteImageMimeType("/repo/image.xml"))
    }

    @Test
    fun imageResourcesAreLimitedToOneExactLocalGetRequest() {
        assertTrue(isLocalImageRequest(IMAGE_PREVIEW_RESOURCE, "GET"))
        for (url in listOf(
            "https://unknown.example/image", "http://codex-file-preview.invalid/image",
            IMAGE_PREVIEW_RESOURCE + "?path=/etc/passwd", IMAGE_PREVIEW_RESOURCE + "/other",
            "https://codex-file-preview.invalid:443/image", "https://user@codex-file-preview.invalid/image",
            "https://codex-file-preview.invalid.evil/image", "file:///data/data/com.codex.remote/files/secrets",
            "content://com.codex.remote.download-files/downloads/private", "data:text/html,<script>alert(1)</script>",
        )) assertFalse(url, isLocalImageRequest(url, "GET"))
        for (method in listOf("POST", "HEAD", "get", "DELETE")) assertFalse(isLocalImageRequest(IMAGE_PREVIEW_RESOURCE, method))
    }
}
