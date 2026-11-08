package com.codex.remote.data.ssh

import com.codex.remote.domain.RemotePlatform
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Message I/O and diagnostics for one authenticated app-server connection. */
interface AppServerTransport : Closeable {
    val remotePlatform: RemotePlatform
    val codexVersion: String
    val errorReader: BufferedReader
    suspend fun readRemoteFile(path: String): String = error("File preview is unavailable on this connection.")
    suspend fun downloadRemoteFile(path: String, output: OutputStream, onProgress: (Long) -> Unit): Unit =
        error("File downloads are unavailable on this connection.")
    suspend fun uploadRemoteFile(displayName: String, input: InputStream, onProgress: (Long) -> Unit): String =
        error("File uploads are unavailable on this connection.")
    fun readMessage(): String?
    fun writeMessage(message: String)
}
