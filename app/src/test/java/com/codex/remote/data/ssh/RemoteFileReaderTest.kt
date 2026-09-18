package com.codex.remote.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RemoteFileReaderTest {
    @Test fun decodesUtf8AndRemovesTheByteOrderMark() {
        assertEquals("# 数学\n\tα + β\r\n", decodeRemoteText("\uFEFF# 数学\n\tα + β\r\n".toByteArray()))
        assertEquals("", decodeRemoteText(byteArrayOf()))
    }

    @Test fun rejectsBinaryAndMalformedUtf8() {
        for (bytes in listOf(byteArrayOf(0, 1, 2), byteArrayOf(0xC3.toByte(), 0x28), "a\u001Bb".toByteArray())) {
            assertThrows(IOException::class.java) { decodeRemoteText(bytes) }
        }
    }

    @Test fun enforcesTheByteLimitAtItsBoundary() {
        assertEquals(MAX_REMOTE_FILE_BYTES, decodeRemoteText(ByteArray(MAX_REMOTE_FILE_BYTES) { 65 }).length)
        assertThrows(IOException::class.java) { decodeRemoteText(ByteArray(MAX_REMOTE_FILE_BYTES + 1) { 65 }) }
    }
}
