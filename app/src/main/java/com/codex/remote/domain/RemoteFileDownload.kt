package com.codex.remote.domain

import java.io.OutputStream

/** Captures the source connection and path when the user starts a preview or download. */
class RemoteFileDownload(
    val writeTo: suspend (OutputStream, onProgress: (Long) -> Unit) -> Unit,
)
