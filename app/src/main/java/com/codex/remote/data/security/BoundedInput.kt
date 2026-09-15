package com.codex.remote.data.security

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader

internal class InputLimitExceededException(message: String) : IOException(message)

/** Reads at most [maxBytes] plus one byte to distinguish EOF from an oversized stream. */
internal fun InputStream.readBytesBounded(maxBytes: Int): ByteArray {
    require(maxBytes in 1 until Int.MAX_VALUE)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(minOf(maxBytes + 1, 8192))
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            val next = read()
            if (next < 0) return output.toByteArray()
            if (output.size() == maxBytes) throw InputLimitExceededException("Input exceeds the $maxBytes byte limit")
            output.write(next)
        } else {
            if (count > maxBytes - output.size()) throw InputLimitExceededException("Input exceeds the $maxBytes byte limit")
            output.write(buffer, 0, count)
        }
    }
}

/** JSONL uses LF or CRLF. Call on a buffered reader; no oversized line is allocated. */
internal fun Reader.readLineBounded(maxChars: Int): String? {
    require(maxChars > 0)
    val line = StringBuilder(minOf(maxChars, 1024))
    while (true) {
        val next = read()
        if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
        if (next == '\n'.code) {
            if (line.lastOrNull() == '\r') line.setLength(line.length - 1)
            return line.toString()
        }
        if (line.length == maxChars) throw InputLimitExceededException("Remote output exceeds the $maxChars character line limit")
        line.append(next.toChar())
    }
}

/** Bound parser recursion before passing an untrusted document to the JSON parser. */
internal fun checkJsonDepth(value: String, maxDepth: Int = 64) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in value) {
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > maxDepth) throw InputLimitExceededException("Remote JSON nesting exceeds the $maxDepth level limit")
            '}', ']' -> depth--
        }
    }
}
