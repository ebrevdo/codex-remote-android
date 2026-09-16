package com.codex.remote.data.ssh

import com.codex.remote.data.security.readLineBounded
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** One JSON-RPC document per message, independent of the wire framing. */
internal interface MessageStream : Closeable {
    fun readMessage(): String?
    fun writeMessage(message: String)
}

internal class JsonLineMessageStream(input: InputStream, output: OutputStream) : MessageStream {
    private val reader = input.bufferedReader(Charsets.UTF_8)
    private val writer = output.bufferedWriter(Charsets.UTF_8)

    override fun readMessage(): String? = reader.readLineBounded(4 * 1024 * 1024)

    override fun writeMessage(message: String) {
        writer.write(message)
        writer.newLine()
        writer.flush()
    }

    // The owner closes SSH before closing the stream, to unblock pending I/O.
    override fun close() = writer.close()
}
