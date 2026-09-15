package com.codex.remote.data.security

import java.io.InputStream
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test fun exactLimitAndEofAreAccepted() {
        assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3).inputStream().readBytesBounded(3))
        val reader = StringReader("abc\nnext")
        assertEquals("abc", reader.readLineBounded(3))
        assertEquals("next", reader.readLineBounded(4))
        assertNull(reader.readLineBounded(4))
        assertEquals("abc", StringReader("abc\r\n").readLineBounded(4))
    }

    @Test fun infiniteInputStopsAfterOneByteBeyondLimit() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int { reads++; return 42 }
        }
        assertThrows(InputLimitExceededException::class.java) { input.readBytesBounded(100) }
        assertEquals(101, reads)
    }

    @Test fun oversizedUnterminatedLineIsRejected() {
        assertThrows(InputLimitExceededException::class.java) { StringReader("abcde").readLineBounded(4) }
    }

    @Test fun deeplyNestedJsonIsRejectedBeforeParsing() {
        assertThrows(InputLimitExceededException::class.java) { checkJsonDepth("[".repeat(65)) }
        checkJsonDepth("""{"text":"[[[[[[[[[[","ok":[]}""", 2)
    }
}
