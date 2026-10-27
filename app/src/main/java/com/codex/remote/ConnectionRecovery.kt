package com.codex.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread scheduler. It never replays an RPC operation; it only opens a new connection. */
internal class ConnectionRecovery(
    private val scope: CoroutineScope,
    private val checkConnection: suspend () -> Unit,
    private val reconnect: () -> Unit,
    private val onWaiting: (delayMillis: Long?) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private enum class Mode { STOPPED, CONNECTING, CONNECTED, RETRY }
    private var mode = Mode.STOPPED
    private var foreground = false
    private var networkAvailable = true
    private var nextRetryMillis = 1_000L
    private var lastHealthyMillis: Long? = null
    private var job: Job? = null

    fun connecting(resetBackoff: Boolean) {
        mode = Mode.CONNECTING
        if (resetBackoff) nextRetryMillis = 1_000
        cancelJob()
    }

    fun connected() {
        mode = Mode.CONNECTED
        lastHealthyMillis = nowMillis()
        nextRetryMillis = 1_000
        schedule(immediate = false)
    }

    fun failed(retryable: Boolean) {
        mode = if (retryable) Mode.RETRY else Mode.STOPPED
        schedule(immediate = false)
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        schedule(immediate = value)
    }

    fun networkChanged(available: Boolean, changed: Boolean = false) {
        if (networkAvailable == available && !changed) return
        networkAvailable = available
        lastHealthyMillis = null
        schedule(immediate = available)
    }

    fun stop() {
        mode = Mode.STOPPED
        cancelJob()
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private fun schedule(immediate: Boolean) {
        cancelJob()
        val canRun = foreground && networkAvailable
        if (mode == Mode.RETRY) {
            val wait = if (immediate) 0L else nextRetryMillis
            onWaiting(if (canRun) wait else null)
            if (canRun) job = scope.launch {
                delay(wait)
                nextRetryMillis = (nextRetryMillis * 2).coerceAtMost(30_000)
                job = null
                reconnect()
            }
        } else if (mode == Mode.CONNECTED && canRun) {
            val wait = if (immediate) {
                lastHealthyMillis?.let { (20_000 - (nowMillis() - it)).coerceIn(0, 20_000) } ?: 0L
            } else 20_000L
            job = scope.launch {
                delay(wait)
                while (mode == Mode.CONNECTED) {
                    checkConnection()
                    if (mode == Mode.CONNECTED) lastHealthyMillis = nowMillis()
                    delay(20_000)
                }
            }
        }
    }
}
