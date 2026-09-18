package com.codex.remote.data.security

import org.junit.Assert.assertThrows
import org.junit.Test

class InputRateLimitTest {
    @Test fun permitsLongSessionsFarBeyondTheOldLifetimeBudget() {
        var now = 0L
        val limit = InputRateLimit(100, "test", nanoTime = { now })
        repeat(10_000) {
            limit.consume(90)
            now += 60_000_000_000L
        }
    }

    @Test fun rejectsBurstsAndOnlyRefillsAtTheConfiguredRate() {
        var now = 0L
        val limit = InputRateLimit(100, "test", nanoTime = { now })
        limit.consume(100)
        assertThrows(InputLimitExceededException::class.java) { limit.consume() }
        now += 30_000_000_000L
        limit.consume(50)
        assertThrows(InputLimitExceededException::class.java) { limit.consume() }
    }

    @Test fun idleTimeDoesNotAllowAnUnboundedBurstOrBoundaryDoubleBurst() {
        var now = 0L
        val limit = InputRateLimit(100, "test", nanoTime = { now })
        now += 600_000_000_000L
        assertThrows(InputLimitExceededException::class.java) { limit.consume(101) }
        limit.consume(100)
        now += 1_000_000L
        assertThrows(InputLimitExceededException::class.java) { limit.consume(100) }
    }
}
