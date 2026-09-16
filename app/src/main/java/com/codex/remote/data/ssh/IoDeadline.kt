package com.codex.remote.data.ssh

import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
    Thread(task, "ssh-io-deadlines").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

/** Closing the underlying transport also interrupts blocking SSH stream I/O. */
internal fun <T> withIoDeadline(timeoutMillis: Long, abort: () -> Unit, action: () -> T): T {
    val state = AtomicInteger(0) // 0: active, 1: finished, 2: timed out
    val deadline = deadlines.schedule({
        if (state.compareAndSet(0, 2)) {
            runCatching(abort)
        }
    }, timeoutMillis, TimeUnit.MILLISECONDS)
    try {
        return action()
    } finally {
        state.compareAndSet(0, 1)
        deadline.cancel(false)
        if (state.get() == 2) throw SocketTimeoutException("Remote transport operation timed out")
    }
}
