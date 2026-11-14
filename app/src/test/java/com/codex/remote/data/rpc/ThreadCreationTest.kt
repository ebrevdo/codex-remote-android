package com.codex.remote.data.rpc

import com.codex.remote.data.ssh.AppServerTransport
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.RemoteThread
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ThreadCreationTest {
    @Test fun creationResponseRetainsTheThreadAndItsEffectiveDirectoryWithoutListingHistory() = runBlocking {
        val transport = FakeTransport("""{"thread":{"id":"created","name":"New session","cwd":"/raw/project","updatedAt":42,"status":{"type":"idle"},"isPinned":true},"cwd":"/actual/project","model":"remote-model","reasoningEffort":"high","serviceTier":"fast"}""")
        CodexRpcClient(transport).use { client ->
            client.initialize()
            val started = client.startThread("/requested/project", "requested-model", null, "on-request", "auto_review", ":workspace")
            assertEquals(RemoteThread("created", "New session", "/actual/project", 42, "idle", true), started.thread)
            assertEquals("created", started.id)
            assertEquals("/actual/project", started.cwd)
            assertEquals("remote-model", started.model)
            assertEquals("high", started.reasoningEffort)
            assertEquals("fast", started.serviceTier)
            assertEquals(listOf("initialize", "thread/start"), transport.methods)
        }
    }

    @Test fun olderCreationResponsesFallBackToTheThreadOrRequestedDirectory() = runBlocking {
        for (threadCwd in listOf("/thread/project", "")) {
            val transport = FakeTransport("""{"thread":{"id":"created","cwd":"$threadCwd"}}""")
            CodexRpcClient(transport).use { client ->
                client.initialize()
                val started = client.startThread("/requested/project", "model", null, "on-request", "auto_review", ":workspace")
                assertEquals(threadCwd.ifBlank { "/requested/project" }, started.thread.cwd)
                assertEquals("New task", started.thread.title)
            }
        }
    }

    @Test fun startedNotificationCarriesTheFullThreadEvenBeforeItIsListed() = runBlocking {
        val transport = FakeTransport()
        CodexRpcClient(transport).use { client ->
            client.initialize()
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                client.events.filterIsInstance<AppServerEvent.ThreadsChanged>().first()
            }
            transport.messages.put("""{"method":"thread/started","params":{"thread":{"id":"created","preview":"New task","cwd":"/new/project","updatedAt":42,"status":{"type":"idle"}}}}""")
            val changed = withTimeout(2_000) { event.await() }
            assertEquals("created", changed.threadId)
            assertEquals(RemoteThread("created", "New task", "/new/project", 42, "idle"), changed.startedThread)
            assertFalse(changed.removed)
            assertFalse(changed.restored)
        }
    }

    @Test fun incompleteStartedNotificationsStillRequestARefreshWithoutAnUnknownProjectEntry() = runBlocking {
        val transport = FakeTransport()
        CodexRpcClient(transport).use { client ->
            client.initialize()
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                client.events.filterIsInstance<AppServerEvent.ThreadsChanged>().first()
            }
            transport.messages.put("""{"method":"thread/started","params":{"thread":{"id":"created"}}}""")
            val changed = withTimeout(2_000) { event.await() }
            assertEquals("created", changed.threadId)
            assertNull(changed.startedThread)
        }
    }

    private class FakeTransport(private val startResult: String = "{}") : AppServerTransport {
        override val remotePlatform = RemotePlatform.POSIX
        override val codexVersion = "test"
        override val errorReader = "".reader().buffered()
        val messages = LinkedBlockingQueue<String>()
        val methods = mutableListOf<String>()
        override fun readMessage(): String? = messages.take().takeIf { it.isNotEmpty() }
        override fun writeMessage(message: String) {
            val request = Json.parseToJsonElement(message).jsonObject
            val id = request["id"] ?: return
            val method = request.getValue("method").jsonPrimitive.content
            methods += method
            messages.put(buildJsonObject {
                put("id", id)
                put("result", Json.parseToJsonElement(if (method == "thread/start") startResult else "{}"))
            }.toString())
        }
        override fun close() { messages.offer("") }
    }
}
