package com.codex.remote.data.rpc

import com.codex.remote.data.ssh.AppServerTransport
import com.codex.remote.domain.RemotePlatform
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RpcConnectionHealthTest {
    @Test fun silentConnectionFailsHealthCheckAndPublishesDurableFailure() = runBlocking {
        val transport = FakeTransport()
        CodexRpcClient(transport, healthTimeoutMillis = 150).use { client ->
            client.initialize()
            transport.reply = false
            val error = runCatching { withTimeout(5_000) { client.checkHealth() } }.exceptionOrNull()
            assertTrue(error is IOException)
            // Subscribe only after failure: this must not rely on an event collector being active.
            assertNotNull(withTimeout(1_000) { client.failure.filterNotNull().first() })
            assertEquals(1, transport.closes.get())
        }
    }

    @Test fun eofAndWriteErrorsAreVisibleWithoutOpeningAStatusDialog() = runBlocking {
        for (writeError in listOf(false, true)) {
            val transport = FakeTransport()
            CodexRpcClient(transport).use { client ->
                client.initialize()
                if (writeError) {
                    transport.failWrites = true
                    assertTrue(runCatching { client.readAccount() }.exceptionOrNull() is IOException)
                } else {
                    transport.messages.put("")
                }
                assertNotNull(withTimeout(2_000) { client.failure.filterNotNull().first() })
            }
        }
    }

    @Test fun closingClientUnblocksPendingCallsWithoutReplayingThem() = runBlocking {
        val transport = FakeTransport()
        CodexRpcClient(transport).use { client ->
            client.initialize()
            transport.reply = false
            val pending = async { runCatching { client.request("turn/start") } }
            withTimeout(2_000) { transport.unanswered.await() }
            client.close()
            assertTrue(withTimeout(2_000) { pending.await() }.exceptionOrNull() is IOException)
            assertEquals(1, transport.unansweredWrites.get())
            assertNull(client.failure.value) // Intentional disconnect is not a retry trigger.
        }
    }

    @Test fun rpcErrorsAreHealthyResponsesAndRequestTimeoutsCloseTheConnection() = runBlocking {
        val transport = FakeTransport()
        CodexRpcClient(transport, requestTimeoutMillis = 150).use { client ->
            client.initialize()
            transport.rpcError = true
            client.checkHealth()
            assertNull(client.failure.value)
            transport.reply = false
            assertTrue(runCatching { client.readAccount() }.exceptionOrNull() is IOException)
            assertNotNull(client.failure.value)
        }
    }

    @Test fun cancellingForegroundProbeDoesNotMarkHealthyConnectionAsFailed() = runBlocking {
        val transport = FakeTransport()
        CodexRpcClient(transport).use { client ->
            client.initialize()
            transport.reply = false
            val probe = async { client.checkHealth() }
            withTimeout(2_000) { transport.unanswered.await() }
            probe.cancelAndJoin()
            assertNull(client.failure.value)
            assertEquals(0, transport.closes.get())
            transport.reply = true
            client.checkHealth()
        }
    }

    @Test fun resumeRestoresActiveTurnIdentityAndIdleStateFromTheServer() = runBlocking {
        val transport = FakeTransport()
        CodexRpcClient(transport).use { client ->
            client.initialize()
            transport.resumeResult = """{"thread":{"status":{"type":"active"}},"initialTurnsPage":{"data":[{"id":"running-turn","status":"inProgress","items":[]}]}}"""
            val active = client.resumeThread("thread-a", "/project")
            assertEquals(true, active.isTurnRunning)
            assertEquals("running-turn", active.activeTurnId)
            transport.resumeResult = """{"thread":{"status":{"type":"idle"}},"initialTurnsPage":{"data":[]}}"""
            val idle = client.resumeThread("thread-a", "/project")
            assertEquals(false, idle.isTurnRunning)
            assertNull(idle.activeTurnId)
        }
    }

    private class FakeTransport : AppServerTransport {
        override val remotePlatform = RemotePlatform.POSIX
        override val codexVersion = "test"
        override val errorReader = "".reader().buffered()
        val messages = LinkedBlockingQueue<String>()
        val closes = AtomicInteger()
        val unansweredWrites = AtomicInteger()
        val unanswered = CompletableDeferred<Unit>()
        @Volatile var reply = true
        @Volatile var failWrites = false
        @Volatile var rpcError = false
        @Volatile var resumeResult: String? = null
        override fun readMessage(): String? = messages.take().takeIf { it.isNotEmpty() }
        override fun writeMessage(message: String) {
            if (failWrites) throw IOException("Broken pipe")
            val request = Json.parseToJsonElement(message).jsonObject
            val id = request["id"]?.jsonPrimitive?.content ?: return
            if (request["method"]?.jsonPrimitive?.content == "thread/resume" && resumeResult != null) {
                messages.put("""{"id":$id,"result":$resumeResult}""")
                return
            }
            if (reply) {
                val payload = if (rpcError) "\"error\":{\"code\":-32601,\"message\":\"unsupported\"}" else "\"result\":{}"
                messages.put("{\"id\":$id,$payload}")
            } else {
                unansweredWrites.incrementAndGet()
                unanswered.complete(Unit)
            }
        }
        override fun close() {
            if (closes.incrementAndGet() == 1) messages.offer("")
        }
    }
}
