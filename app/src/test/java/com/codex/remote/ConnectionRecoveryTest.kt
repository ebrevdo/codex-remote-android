package com.codex.remote

import com.codex.remote.data.security.InputLimitExceededException
import com.codex.remote.data.ssh.HostKeyChangedException
import com.codex.remote.data.ssh.UnknownHostKeyException
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRecoveryTest {
    @Test fun retriesWithBackoffAndStopsWhenExplicitlyDisconnected() = runTest {
        val attempts = mutableListOf<Long>()
        lateinit var recovery: ConnectionRecovery
        recovery = ConnectionRecovery(backgroundScope, {}, {
            attempts += testScheduler.currentTime
            recovery.connecting(resetBackoff = false)
            recovery.failed(true)
        }, {})
        recovery.setForeground(true)
        recovery.failed(true)
        advanceTimeBy(63_001)
        assertEquals(listOf(1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 61_000L), attempts)
        recovery.stop()
        advanceTimeBy(120_000)
        assertEquals(6, attempts.size)
    }

    @Test fun backgroundAndOfflinePauseRetriesAndReturnRetriesImmediately() = runTest {
        var attempts = 0
        lateinit var recovery: ConnectionRecovery
        recovery = ConnectionRecovery(backgroundScope, {}, {
            attempts++
            recovery.connecting(resetBackoff = false)
        }, {})
        recovery.failed(true)
        advanceTimeBy(60_000)
        assertEquals(0, attempts)
        recovery.networkChanged(false)
        recovery.setForeground(true)
        advanceTimeBy(60_000)
        assertEquals(0, attempts)
        recovery.networkChanged(true)
        runCurrent()
        assertEquals(1, attempts)
        recovery.failed(true)
        recovery.setForeground(false)
        advanceTimeBy(60_000)
        assertEquals(1, attempts)
        recovery.setForeground(true)
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test fun probesOnReturnAndPeriodicallyButNotInBackground() = runTest {
        var checks = 0
        val recovery = ConnectionRecovery(backgroundScope, { checks++ }, {}, {})
        recovery.connected()
        recovery.setForeground(true)
        runCurrent()
        assertEquals(1, checks)
        advanceTimeBy(20_001)
        assertEquals(2, checks)
        recovery.setForeground(false)
        advanceTimeBy(120_000)
        assertEquals(2, checks)
        recovery.setForeground(true)
        runCurrent()
        assertEquals(3, checks)
    }

    @Test fun foregroundChangesCancelOldProbesWithoutOverlappingThem() = runTest {
        var active = 0
        var maxActive = 0
        val recovery = ConnectionRecovery(backgroundScope, {
            active++
            maxActive = maxOf(maxActive, active)
            try { awaitCancellation() } finally { active-- }
        }, {}, {})
        recovery.connected()
        recovery.setForeground(true)
        runCurrent()
        recovery.setForeground(false)
        recovery.setForeground(true)
        runCurrent()
        assertEquals(1, maxActive)
        recovery.stop()
        runCurrent()
        assertEquals(0, active)
    }

    @Test fun securityFailuresCannotBeRetriedByNetworkOrForegroundChanges() = runTest {
        var attempts = 0
        val recovery = ConnectionRecovery(backgroundScope, {}, { attempts++ }, {})
        recovery.setForeground(true)
        recovery.failed(false)
        recovery.networkChanged(true)
        recovery.setForeground(false)
        recovery.setForeground(true)
        advanceTimeBy(120_000)
        assertEquals(0, attempts)
        for (error in listOf(
            UnknownHostKeyException("fingerprint"), HostKeyChangedException("old", "new"),
            InputLimitExceededException("flood"), IllegalArgumentException("bad configuration"),
        )) assertFalse(isRetryableConnectionFailure(IOException("wrapper", error)))
        assertTrue(isRetryableConnectionFailure(IOException("network lost")))
    }
}
