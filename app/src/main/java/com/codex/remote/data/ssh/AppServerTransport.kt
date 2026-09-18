package com.codex.remote.data.ssh

import com.codex.remote.domain.RemotePlatform
import java.io.BufferedReader
import java.io.Closeable

/** Message I/O and diagnostics for one authenticated app-server connection. */
interface AppServerTransport : Closeable {
    val remotePlatform: RemotePlatform
    val codexVersion: String
    val errorReader: BufferedReader
    fun readMessage(): String?
    fun writeMessage(message: String)
}
