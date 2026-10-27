package com.codex.remote.data.ssh

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class RemoteFileDownloaderTest {
    @Test
    fun preservesRawBytesIncludingBinaryInvalidUtf8AndBom() {
        val original = ByteArray(300_001) { (it % 256).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        // Short reads must not be confused with EOF.
        val input = object : ByteArrayInputStream(original) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 137))
        }
        val copied = copyDownload(input, output, progress::add) {}
        assertEquals(original.size.toLong(), copied)
        assertArrayEquals(original, output.toByteArray())
        assertEquals(0L, progress.first())
        assertEquals(copied, progress.last())
    }

    @Test
    fun streamsBeyondTwoGiBWithFixedMemoryAndLongByteCounts() {
        val size = Int.MAX_VALUE.toLong() + 65_537L
        var remaining = size
        var written = 0L
        var lastProgress = 0L
        val input = object : InputStream() {
            override fun read(): Int = error("Expected buffered reads")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertTrue("Memory must not grow with file size", b.size <= 32 * 1024)
                if (remaining == 0L) return -1
                val count = minOf(remaining, len.toLong()).toInt()
                remaining -= count
                return count
            }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) = error("Expected buffered writes")
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
        }
        assertEquals(size, copyDownload(input, output, { lastProgress = it }) {})
        assertEquals(size, written)
        assertEquals(size, lastProgress)
    }

    @Test
    fun emptyFilesCompleteWithoutInventingBytes() {
        val output = ByteArrayOutputStream()
        assertEquals(0L, copyDownload(ByteArrayInputStream(byteArrayOf()), output, {}) {})
        assertEquals(0, output.size())
    }

    @Test
    fun cancellationBetweenReadingAndWritingDoesNotWriteTheChunk() {
        var canceled = false
        val input = object : ByteArrayInputStream(ByteArray(100)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                canceled = true
                return super.read(b, off, len)
            }
        }
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            copyDownload(input, output, {}) { if (canceled) throw CancellationException() }
        }
        assertEquals(0, output.size())
    }

    @Test
    fun failedDestinationStopsReadingAndDoesNotReportCompletion() {
        val input = ByteArrayInputStream(ByteArray(100_000))
        val failure = IOException("Storage full")
        val output = object : OutputStream() {
            override fun write(b: Int) { throw failure }
            override fun write(b: ByteArray, off: Int, len: Int) { throw failure }
        }
        val progress = mutableListOf<Long>()
        assertSame(failure, assertThrows(IOException::class.java) { copyDownload(input, output, progress::add) {} })
        assertTrue(input.available() > 0)
        assertEquals(listOf(0L), progress)
    }
}
