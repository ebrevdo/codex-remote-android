package com.codex.remote.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSocketMessageStreamTest {
    @Test
    fun exchangesMaskedJsonWithAnIndependentWebSocketPeer() {
        Peer({ socket ->
            upgrade(socket)
            val frame = readClientFrame(socket.getInputStream())
            assertEquals(1, frame.first)
            assertEquals("{\"id\":1}", frame.second.toString(Charsets.UTF_8))
            send(socket, 0x81, "{\"id\":1,\"result\":{}}".toByteArray())
        }).use { peer ->
            peer.stream.connect()
            peer.stream.writeMessage("{\"id\":1}")
            assertEquals("{\"id\":1,\"result\":{}}", peer.stream.readMessage())
            peer.await()
        }
    }

    @Test
    fun reassemblesFragmentsAndAnswersPing() {
        Peer({ socket ->
            upgrade(socket)
            send(socket, 0x01, "{\"value\":".toByteArray())
            send(socket, 0x89, "ping".toByteArray())
            val pong = readClientFrame(socket.getInputStream())
            assertEquals(10, pong.first)
            assertEquals("ping", pong.second.toString(Charsets.UTF_8))
            send(socket, 0x80, "\"hello 🌍\"}".toByteArray())
        }).use { peer ->
            peer.stream.connect()
            assertEquals("{\"value\":\"hello 🌍\"}", peer.stream.readMessage())
            peer.await()
        }
    }

    @Test
    fun returnsSeparateMessagesFromOneReadIncludingEmbeddedNewlines() {
        Peer({ socket ->
            upgrade(socket)
            socket.getOutputStream().write(frame(0x81, "{\n\"id\":1\n}".toByteArray()) + frame(0x81, "{}".toByteArray()))
        }).use { peer ->
            peer.stream.connect()
            assertEquals("{\n\"id\":1\n}", peer.stream.readMessage())
            assertEquals("{}", peer.stream.readMessage())
            peer.await()
        }
    }

    @Test
    fun rejectsOversizedFrameFromItsHeaderBeforeReadingPayload() {
        Peer({ socket ->
            upgrade(socket)
            socket.getOutputStream().write(byteArrayOf(0x81.toByte(), 127) + ByteBuffer.allocate(8).putLong(1_000_000).array())
            readClientFrame(socket.getInputStream()) // Protocol close, without sending the advertised payload.
        }, maxMessageBytes = 32).use { peer ->
            assertThrows(IOException::class.java) {
                peer.stream.connect()
                peer.stream.readMessage()
            }
            peer.await()
        }
    }

    @Test
    fun rejectsOversizedFragmentedMessage() {
        Peer({ socket ->
            upgrade(socket)
            socket.getOutputStream().write(frame(0x01, "123456".toByteArray()) + frame(0x80, "789012".toByteArray()))
            readClientFrame(socket.getInputStream())
        }, maxMessageBytes = 8).use { peer ->
            assertThrows(IOException::class.java) {
                peer.stream.connect()
                peer.stream.readMessage()
            }
            peer.await()
        }
    }

    @Test
    fun boundsEmptyFragmentFloods() {
        Peer({ socket ->
            upgrade(socket)
            socket.getOutputStream().write(frame(0x01, byteArrayOf()) + ByteArray(1024 * 2))
            readClientFrame(socket.getInputStream())
        }).use { peer ->
            assertThrows(IOException::class.java) {
                peer.stream.connect()
                peer.stream.readMessage()
            }
            peer.await()
        }
    }

    @Test
    fun rejectsBinaryMessages() {
        Peer({ socket ->
            upgrade(socket)
            send(socket, 0x82, byteArrayOf(1, 2, 3))
            readClientFrame(socket.getInputStream())
        }).use { peer ->
            assertThrows(IOException::class.java) {
                peer.stream.connect()
                peer.stream.readMessage()
            }
            peer.await()
        }
    }

    @Test
    fun echoesNormalCloseAndReturnsEndOfStream() {
        Peer({ socket ->
            upgrade(socket)
            send(socket, 0x88, byteArrayOf(3, 0xe8.toByte()))
            assertEquals(8, readClientFrame(socket.getInputStream()).first)
        }).use { peer ->
            peer.stream.connect()
            assertNull(peer.stream.readMessage())
            peer.await()
        }
    }

    @Test
    fun rejectsInvalidHandshake() {
        Peer({ socket ->
            readHeaders(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: invalid\r\n\r\n".toByteArray())
        }).use { peer ->
            assertThrows(IOException::class.java) { peer.stream.connect() }
            peer.await()
        }
    }

    @Test
    fun boundsUnterminatedHandshake() {
        Peer({ socket ->
            readHeaders(socket.getInputStream())
            socket.getOutputStream().write(ByteArray(17 * 1024) { 'A'.code.toByte() })
        }).use { peer ->
            assertThrows(IOException::class.java) { peer.stream.connect() }
            peer.await()
        }
    }

    @Test
    fun handshakeTimeoutClosesBlockedRead() {
        Peer({ socket ->
            readHeaders(socket.getInputStream())
            assertEquals(-1, socket.getInputStream().read())
        }, timeoutMillis = 200).use { peer ->
            assertThrows(SocketTimeoutException::class.java) { peer.stream.connect() }
            peer.await()
        }
    }

    @Test
    fun closeInterruptsReadWithoutWaitingForTheEngineLock() {
        val release = CountDownLatch(1)
        Peer({ socket ->
            upgrade(socket)
            release.await(3, TimeUnit.SECONDS)
        }).use { peer ->
            peer.stream.connect()
            val reader = Executors.newSingleThreadExecutor()
            try {
                val future = reader.submit { runCatching { peer.stream.readMessage() } }
                peer.stream.close()
                future.get(2, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                reader.shutdownNow()
            }
        }
    }

    @Test
    fun fragmentsLargeOutgoingRequestsIntoBoundedFrames() {
        val message = "x".repeat(1024 * 1024 + 3)
        Peer({ socket ->
            upgrade(socket)
            val first = readClientFrame(socket.getInputStream())
            val last = readClientFrame(socket.getInputStream())
            assertEquals(1, first.first)
            assertEquals(0, last.first)
            assertEquals(message, (first.second + last.second).toString(Charsets.UTF_8))
        }).use { peer ->
            peer.stream.connect()
            peer.stream.writeMessage(message)
            peer.await()
        }
    }

    private class Peer(
        serve: (Socket) -> Unit,
        timeoutMillis: Long = 2_000,
        maxMessageBytes: Int = 4 * 1024 * 1024,
    ) : Closeable {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        private val client = Socket(listener.inetAddress, listener.localPort)
        private val executor = Executors.newSingleThreadExecutor()
        private val task = executor.submit {
            listener.accept().use { socket ->
                socket.soTimeout = 3_000
                serve(socket)
            }
        }
        val stream = WebSocketMessageStream(client.getInputStream(), client.getOutputStream(), { client.close() }, timeoutMillis, maxMessageBytes)
        fun await() { task.get(5, TimeUnit.SECONDS) }
        override fun close() {
            stream.close()
            listener.close()
            executor.shutdownNow()
        }
    }

    companion object {
        private fun readHeaders(input: InputStream): String {
            val data = StringBuilder()
            while (!data.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0 && data.length < 16 * 1024)
                data.append(byte.toChar())
            }
            return data.toString()
        }

        private fun upgrade(socket: Socket) {
            val headers = readHeaders(socket.getInputStream())
            assertTrue(headers.startsWith("GET / HTTP/1.1\r\n"))
            val key = headers.lineSequence().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        }

        private fun frame(opcode: Int, data: ByteArray): ByteArray {
            require(data.size < 126)
            return byteArrayOf(opcode.toByte(), data.size.toByte()) + data
        }

        private fun send(socket: Socket, opcode: Int, data: ByteArray) = socket.getOutputStream().write(frame(opcode, data))

        private fun readClientFrame(input: InputStream): Pair<Int, ByteArray> {
            val flags = input.read()
            val sizeByte = input.read()
            check(flags >= 0 && sizeByte >= 0)
            assertTrue("Client frames must be masked", sizeByte and 128 != 0)
            val size = when (val small = sizeByte and 127) {
                126 -> ByteBuffer.wrap(input.readNBytes(2)).short.toInt() and 65535
                127 -> ByteBuffer.wrap(input.readNBytes(8)).long.toInt()
                else -> small
            }
            require(size in 0..2 * 1024 * 1024)
            val mask = input.readNBytes(4)
            val payload = input.readNBytes(size)
            assertEquals(size, payload.size)
            payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            return (flags and 15) to payload
        }
    }
}
