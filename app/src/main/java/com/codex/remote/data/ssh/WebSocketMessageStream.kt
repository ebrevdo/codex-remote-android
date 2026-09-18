package com.codex.remote.data.ssh

import com.codex.remote.data.security.InputRateLimit
import com.codex.remote.data.security.InputLimitExceededException
import org.java_websocket.WebSocket
import org.java_websocket.WebSocketAdapter
import org.java_websocket.WebSocketImpl
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.Opcode
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.exceptions.LimitExceededException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.handshake.Handshakedata
import org.java_websocket.handshake.HandshakeImpl1Client
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** WebSocket framing over an authenticated SSH exec channel; never opens a network socket. */
internal class WebSocketMessageStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val abort: () -> Unit,
    private val timeoutMillis: Long = 15_000,
    private val maxMessageBytes: Int = 4 * 1024 * 1024,
) : MessageStream {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val messages = ArrayDeque<String>()
    private var queuedChars = 0
    private val inputRate = InputRateLimit(64L * 1024 * 1024, "WebSocket traffic")
    private var handshakeBytes = 0
    private var handshakeComplete = false
    private var failure: IOException? = null
    private val buffer = ByteArray(8192)
    private val engine = WebSocketImpl(Listener(), BoundedDraft(maxMessageBytes))

    fun connect() = withIoDeadline(timeoutMillis, ::close) {
        synchronized(lock) {
            engine.startHandshake(HandshakeImpl1Client().apply {
                resourceDescriptor = "/"
                put("Host", "localhost") // HTTP authority only; no DNS lookup or TCP connection.
            })
            checkFailure()
        }
        while (!handshakeComplete) {
            if (!readBytes()) throw IOException("Codex proxy closed before the WebSocket handshake")
            synchronized(lock) {
                checkFailure()
                if (!handshakeComplete && (engine.isClosing || engine.isClosed)) throw IOException("Codex proxy rejected the WebSocket handshake")
            }
        }
    }

    // Only the RPC reader calls readMessage; writes and close may run concurrently.
    override fun readMessage(): String? {
        while (true) {
            synchronized(lock) {
                checkFailure()
                messages.pollFirst()?.let {
                    queuedChars -= it.length
                    return it
                }
                if (closed.get() || engine.isClosing || engine.isClosed) return null
            }
            if (!readBytes()) {
                synchronized(lock) {
                    if (!closed.get()) {
                        engine.eot()
                        checkFailure()
                    }
                }
                return null
            }
        }
    }

    override fun writeMessage(message: String) {
        if (message.length > MAX_OUTGOING_BYTES) throw InputLimitExceededException("Request exceeds the 64 MiB WebSocket limit")
        val bytes = message.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_OUTGOING_BYTES) throw InputLimitExceededException("Request exceeds the 64 MiB WebSocket limit")
        synchronized(lock) {
            checkFailure()
            if (closed.get() || !engine.isOpen) throw IOException("Codex WebSocket is closed")
            withIoDeadline(timeoutMillis, ::close) {
                // Keep each wire frame small, including image-bearing requests.
                var offset = 0
                do {
                    val count = minOf(bytes.size - offset, OUTGOING_FRAME_BYTES)
                    engine.sendFragmentedFrame(Opcode.TEXT, ByteBuffer.wrap(bytes, offset, count), offset + count == bytes.size)
                    checkFailure()
                    offset += count
                } while (offset < bytes.size)
            }
        }
    }

    private fun readBytes(): Boolean {
        val count = input.read(buffer)
        if (count < 0) return false
        if (count == 0) return true
        synchronized(lock) {
            if (closed.get()) return false
            inputRate.consume(count.toLong())
            if (!engine.isOpen) {
                handshakeBytes += count
                if (handshakeBytes > MAX_HANDSHAKE_BYTES) throw InputLimitExceededException("WebSocket handshake exceeds 16 KiB")
            }
            engine.decode(ByteBuffer.wrap(buffer, 0, count))
            checkFailure()
        }
        return true
    }

    private fun checkFailure() {
        failure?.let { throw it }
    }

    // No lock: closing SSH must unblock a writer that holds the engine lock.
    override fun close() {
        if (closed.compareAndSet(false, true)) abort()
    }

    private inner class Listener : WebSocketAdapter() {
        override fun onWebsocketMessage(conn: WebSocket, message: String) {
            if (failure != null || closed.get()) return
            if (messages.size >= MAX_QUEUED_MESSAGES || message.length > maxMessageBytes - queuedChars) {
                failure = InputLimitExceededException("WebSocket receive queue exceeded its limit")
                return
            }
            messages.addLast(message)
            queuedChars += message.length
        }

        override fun onWebsocketMessage(conn: WebSocket, blob: ByteBuffer) {
            failure = IOException("Expected a JSON text WebSocket message")
        }

        override fun onWebsocketHandshakeReceivedAsClient(conn: WebSocket, request: ClientHandshake, response: ServerHandshake) {
            if (response.getFieldValue("Sec-WebSocket-Extensions").isNotBlank() ||
                response.getFieldValue("Sec-WebSocket-Protocol").isNotBlank()
            ) throw InvalidDataException(CloseFrame.PROTOCOL_ERROR, "Unexpected WebSocket extension or subprotocol")
        }

        override fun onWebsocketOpen(conn: WebSocket, handshake: Handshakedata) { handshakeComplete = true }
        override fun getLocalSocketAddress(conn: WebSocket): InetSocketAddress? = null
        override fun getRemoteSocketAddress(conn: WebSocket): InetSocketAddress? = null
        override fun onWebsocketCloseInitiated(conn: WebSocket, code: Int, reason: String?) = Unit

        override fun onWebsocketClosing(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            if (code != CloseFrame.NORMAL && code != CloseFrame.GOING_AWAY && failure == null) {
                failure = IOException("Codex WebSocket closed (code $code): ${reason.orEmpty().take(256)}")
            }
        }

        override fun onWebsocketClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) =
            onWebsocketClosing(conn, code, reason, remote)

        override fun onWebsocketError(conn: WebSocket, error: Exception) {
            if (failure == null) failure = IOException("Codex WebSocket failed", error)
        }

        override fun onWriteDemand(conn: WebSocket) {
            val socket = conn as WebSocketImpl
            if (closed.get()) {
                socket.outQueue.clear()
                return
            }
            try {
                withIoDeadline(timeoutMillis, ::close) {
                    // Drain synchronously: the library's unbounded outgoing queue cannot accumulate.
                    while (true) {
                        val frame = socket.outQueue.poll() ?: break
                        output.write(frame.array(), frame.arrayOffset() + frame.position(), frame.remaining())
                    }
                    output.flush()
                }
            } catch (error: IOException) {
                failure = error
                close()
            }
        }
    }

    /** The library bounds bytes; also bound zero-length fragments and control-frame floods. */
    private class BoundedDraft(private val messageLimit: Int) : Draft_6455(emptyList(), messageLimit) {
        private var fragments = 0
        private val frameRate = InputRateLimit(100_000, "WebSocket frames")

        override fun copyInstance(): Draft = BoundedDraft(messageLimit)

        override fun processFrame(socket: WebSocketImpl, frame: Framedata) {
            try {
                frameRate.consume()
            } catch (error: InputLimitExceededException) {
                throw LimitExceededException(error.message, 100_000)
            }
            when (frame.opcode) {
                Opcode.BINARY -> throw InvalidDataException(CloseFrame.REFUSE, "Expected JSON text")
                Opcode.TEXT, Opcode.CONTINUOUS -> {
                    if (++fragments > MAX_FRAGMENTS) throw LimitExceededException("WebSocket fragment limit exceeded", MAX_FRAGMENTS)
                    if (frame.isFin) fragments = 0
                }
                else -> Unit
            }
            super.processFrame(socket, frame)
        }
    }

    companion object {
        private const val MAX_HANDSHAKE_BYTES = 16 * 1024
        private const val MAX_FRAGMENTS = 1024
        private const val MAX_QUEUED_MESSAGES = 128
        private const val MAX_OUTGOING_BYTES = 64 * 1024 * 1024
        private const val OUTGOING_FRAME_BYTES = 1024 * 1024
    }
}
