package com.codex.remote.data.security

import java.util.Locale

internal fun remoteFileName(path: String): String = path.replace('\\', '/')
    .substringAfterLast('/')
    .map { if (it.isISOControl()) '_' else it }.joinToString("")
    .trim()
    .takeUnless { it.isEmpty() || it == "." || it == ".." }
    ?: "download"

internal fun remoteFileExtension(path: String): String = remoteFileName(path)
    .substringAfterLast('.', "").lowercase(Locale.ROOT)

internal fun remoteImageMimeType(path: String): String? = when (remoteFileExtension(path)) {
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg", "jpe" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "ico" -> "image/x-icon"
    "avif" -> "image/avif"
    else -> null
}

// The WebView serves exactly one local image. Every other request is blocked.
internal const val IMAGE_PREVIEW_ORIGIN = "https://codex-file-preview.invalid/"
internal const val IMAGE_PREVIEW_RESOURCE = IMAGE_PREVIEW_ORIGIN + "image"
internal fun isLocalImageRequest(url: String, method: String): Boolean =
    method == "GET" && url == IMAGE_PREVIEW_RESOURCE
