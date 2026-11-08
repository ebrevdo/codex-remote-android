package com.codex.remote.domain

import java.io.InputStream

/** Captures the destination connection before opening Android's document picker. */
class RemoteFileUpload(
    val writeFrom: suspend (displayName: String, input: InputStream, onProgress: (Long) -> Unit) -> String,
)
