package com.codex.remote.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebSocketLimitsTest {
    @Test
    fun boundsNumberOfQueuedMessagesEvenWhenEachMessageIsEmpty() {
        val peer = MemoryPeer(ByteArray(129 * 2) { if (it % 2 == 0) 0x81.toByte() else 0 })
        val error = assertThrows(IOException::class.java) { peer.stream.connect() }
        assertTrue(error.message.orEmpty().contains("queue"))
        peer.stream.close()
    }

    @Test
    fun boundsTotalQueuedCharactersAcrossIndividuallyValidMessages() {
        val frame = byteArrayOf(0x81.toByte(), 5) + "12345".toByteArray()
        val peer = MemoryPeer(frame + frame, maxMessageBytes = 8)
        val error = assertThrows(IOException::class.java) { peer.stream.connect() }
        assertTrue(error.message.orEmpty().contains("queue"))
        peer.stream.close()
    }

    @Test
    fun refusesUnsolicitedCompressionOrSubprotocols() {
        for (header in listOf("Sec-WebSocket-Extensions: permessage-deflate\r\n", "Sec-WebSocket-Protocol: unknown\r\n")) {
            val peer = MemoryPeer(extraHeaders = header)
            assertThrows(IOException::class.java) { peer.stream.connect() }
            peer.stream.close()
        }
    }

    @Test
    fun writeDeadlineUnblocksTheWriterAndClosesTheTransportOnce() {
        val peer = MemoryPeer(timeoutMillis = 100)
        peer.stream.connect()
        peer.blockWrites = true
        assertThrows(SocketTimeoutException::class.java) { peer.stream.writeMessage("{}") }
        assertEquals(1, peer.closes)
        peer.stream.close()
        assertEquals(1, peer.closes)
    }

    @Test
    fun unexpectedEofIsAnErrorInsteadOfAJsonMessage() {
        val peer = MemoryPeer()
        peer.stream.connect()
        assertThrows(IOException::class.java) { peer.stream.readMessage() }
        peer.stream.close()
    }

    /** Return the handshake and all frames in one read, independent of TCP packet timing. */
    private class MemoryPeer(
        frames: ByteArray = byteArrayOf(),
        extraHeaders: String = "",
        maxMessageBytes: Int = 4 * 1024 * 1024,
        timeoutMillis: Long = 1_000,
    ) {
        private val written = ByteArrayOutputStream()
        private val released = CountDownLatch(1)
        var blockWrites = false
        var closes = 0
        private val input = object : InputStream() {
            private val bytes by lazy {
                val key = written.toByteArray().toString(Charsets.US_ASCII).lineSequence().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                ByteArrayInputStream(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n" + extraHeaders + "\r\n").toByteArray() + frames)
            }
            override fun read(): Int = bytes.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = bytes.read(buffer, offset, length)
        }
        private val output = object : OutputStream() {
            override fun write(value: Int) { written.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (blockWrites) check(released.await(2, TimeUnit.SECONDS)) { "Write was not interrupted" }
                written.write(buffer, offset, length)
            }
        }
        val stream = WebSocketMessageStream(input, output, {
            closes++
            released.countDown()
        }, timeoutMillis, maxMessageBytes)
    }
}
