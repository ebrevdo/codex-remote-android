package com.codex.remote.data.security

/** A monotonic token bucket: bounded bursts and sustained traffic, with no connection lifetime cap. */
internal class InputRateLimit(
    private val capacity: Long,
    private val label: String,
    private val refillMillis: Long = 60_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var tokens = capacity.toDouble()
    private var lastRefill = nanoTime()

    init {
        require(capacity > 0 && refillMillis > 0)
    }

    fun consume(amount: Long = 1) {
        require(amount >= 0)
        val now = nanoTime()
        val elapsed = (now - lastRefill).coerceAtLeast(0)
        lastRefill = now
        tokens = minOf(capacity.toDouble(), tokens + elapsed.toDouble() / 1_000_000 / refillMillis * capacity)
        if (amount > tokens) throw InputLimitExceededException("$label exceeded its input rate limit")
        tokens -= amount
    }
}
