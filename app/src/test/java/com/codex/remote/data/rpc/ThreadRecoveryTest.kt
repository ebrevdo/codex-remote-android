package com.codex.remote.data.rpc

import com.codex.remote.data.ssh.AppServerTransport
import com.codex.remote.domain.RemotePlatform
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ThreadRecoveryTest {
    @Test fun historyUsesTheDatabaseAndSmallNewestFirstPageThenLargerOlderPages() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val transport = FakeTransport { method, params ->
            if (method == "thread/list") {
                requests += params
                if (params["cursor"] == null) {
                    result("""{"data":[{"id":"new","updatedAt":2}],"nextCursor":"older"}""")
                } else {
                    result("""{"data":[{"id":"old","updatedAt":1}]}""")
                }
            } else result("{}")
        }
        CodexRpcClient(transport).use { client ->
            client.initialize()
            assertEquals(listOf("new", "old"), client.listThreads().map { it.id })
            assertEquals(listOf(20, 100), requests.map { it.getValue("limit").jsonPrimitive.content.toInt() })
            assertTrue(requests.all { it.getValue("useStateDbOnly").jsonPrimitive.content == "true" })
            assertTrue(requests.all { it.getValue("sortKey").jsonPrimitive.content == "updated_at" })
            assertTrue(requests.all { it.getValue("sortDirection").jsonPrimitive.content == "desc" })
        }
    }

    @Test fun unsupportedDatabaseListingFallsBackOnceAndRemembersCompatibility() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val transport = FakeTransport { method, params ->
            if (method == "thread/list") {
                requests += params
                if (params["useStateDbOnly"] != null) rejection("unknown field useStateDbOnly")
                else result("""{"data":[{"id":"saved"}]}""")
            } else result("{}")
        }
        CodexRpcClient(transport).use { client ->
            client.initialize()
            repeat(2) { assertEquals("saved", client.listThreads().single().id) }
            assertEquals(3, requests.size)
            assertTrue(requests.first().containsKey("useStateDbOnly"))
            assertTrue(requests.drop(1).all { !it.containsKey("useStateDbOnly") })
        }
    }

    @Test fun emptyArchiveIndexFallbackDoesNotDisableFastActiveHistory() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val transport = FakeTransport { method, params ->
            if (method == "thread/list") {
                requests += params
                if (params.getValue("archived").jsonPrimitive.content == "true") result("""{"data":[]}""")
                else result("""{"data":[{"id":"saved"}]}""")
            } else result("{}")
        }
        CodexRpcClient(transport).use { client ->
            client.initialize()
            assertTrue(client.listArchivedThreads().isEmpty())
            assertEquals("saved", client.listThreads().single().id)
            assertEquals(listOf(true, false, true), requests.map { it.containsKey("useStateDbOnly") })
        }
    }

    @Test fun emptyActiveIndexScansForUnindexedSessionsBeforeReportingNoHistory() = runBlocking {
        val transport = FakeTransport { method, params ->
            if (method == "thread/list") {
                if (params.containsKey("useStateDbOnly")) result("""{"data":[]}""")
                else result("""{"data":[{"id":"unindexed"}]}""")
            } else result("{}")
        }
        CodexRpcClient(transport).use { client ->
            client.initialize()
            assertEquals("unindexed", client.listThreads().single().id)
            assertEquals(2, transport.methods.count { it == "thread/list" })
        }
    }

    @Test fun ordinaryListErrorsDoNotTriggerASecondSlowScan() = runBlocking {
        val transport = FakeTransport { method, _ ->
            if (method == "thread/list") rejection("permission denied") else result("{}")
        }
        CodexRpcClient(transport).use { client ->
            client.initialize()
            assertTrue(runCatching { client.listThreads() }.exceptionOrNull() is RpcException)
            assertEquals(1, transport.methods.count { it == "thread/list" })
        }
    }

    @Test fun explicitlyMissingConversationsResumeTheSameThreadAndRetryOnce() = runBlocking {
        for (message in listOf(
            "thread not found: parent", "Conversation ID parent not found", "Conversation not found: parent",
        )) {
            var turns = 0
            val transport = FakeTransport { method, params ->
                when (method) {
                    "turn/start" -> if (++turns == 1) rejection(message) else result("{}")
                    "thread/resume" -> {
                        assertEquals("parent", params.getValue("threadId").jsonPrimitive.content)
                        assertEquals("/project", params.getValue("cwd").jsonPrimitive.content)
                        idleSession()
                    }
                    else -> result("{}")
                }
            }
            CodexRpcClient(transport).use { client ->
                client.initialize()
                val inputs = mutableListOf<Boolean?>()
                client.withLoadedThread("parent", "/project") { restored ->
                    inputs += restored?.isTurnRunning
                    client.request("turn/start", buildJsonObject { put("threadId", "parent") })
                }
                assertEquals(listOf(null, false), inputs)
                assertEquals(listOf("initialize", "turn/start", "thread/resume", "turn/start"), transport.methods)
            }
        }
    }

    @Test fun unrelatedRpcErrorsNeverResumeOrResend() = runBlocking {
        for (message in listOf("file not found: parent", "thread not found: another", "turn not found: parent")) {
            val transport = FakeTransport { method, _ ->
                if (method == "turn/start") rejection(message) else result("{}")
            }
            CodexRpcClient(transport).use { client ->
                client.initialize()
                val failure = runCatching {
                    client.withLoadedThread("parent", "/project") { client.request("turn/start") }
                }.exceptionOrNull()
                assertTrue(failure is RpcException)
                assertEquals(listOf("initialize", "turn/start"), transport.methods)
            }
        }
    }

    @Test fun uncertainDeliveryIsNeverRetried() = runBlocking {
        val transport = FakeTransport { method, _ ->
            if (method == "turn/start") throw IOException("broken pipe")
            result("{}")
        }
        CodexRpcClient(transport).use { client ->
            client.initialize()
            val failure = runCatching {
                client.withLoadedThread("parent", "/project") { client.request("turn/start") }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(listOf("initialize", "turn/start"), transport.methods)
        }
    }

    @Test fun failedResumeOrRepeatedRejectionStopsRecovery() = runBlocking {
        for (resumeFails in listOf(false, true)) {
            val transport = FakeTransport { method, _ ->
                when (method) {
                    "turn/start" -> rejection("thread not found: parent")
                    "thread/resume" -> if (resumeFails) rejection("thread not found: parent") else idleSession()
                    else -> result("{}")
                }
            }
            CodexRpcClient(transport).use { client ->
                client.initialize()
                val failure = runCatching {
                    client.withLoadedThread("parent", "/project") { client.request("turn/start") }
                }.exceptionOrNull()
                assertTrue(failure is RpcException)
                assertEquals(if (resumeFails) 1 else 2, transport.methods.count { it == "turn/start" })
                assertEquals(1, transport.methods.count { it == "thread/resume" })
            }
        }
    }

    private fun idleSession() = result("""{"thread":{"status":{"type":"idle"}},"initialTurnsPage":{"data":[]}}""")

    private fun rejection(message: String) = buildJsonObject {
        put("error", buildJsonObject { put("code", -32600); put("message", message) })
    }

    private fun result(payload: String) = buildJsonObject { put("result", Json.parseToJsonElement(payload)) }

    private class FakeTransport(private val reply: (String, JsonObject) -> JsonObject) : AppServerTransport {
        override val remotePlatform = RemotePlatform.POSIX
        override val codexVersion = "test"
        override val errorReader = "".reader().buffered()
        private val incoming = LinkedBlockingQueue<String>()
        val methods = mutableListOf<String>()
        override fun readMessage(): String? = incoming.take().takeIf { it.isNotEmpty() }
        override fun writeMessage(message: String) {
            val request = Json.parseToJsonElement(message).jsonObject
            val id = request["id"] ?: return
            val method = request.getValue("method").jsonPrimitive.content
            methods += method
            val response = reply(method, request.getValue("params").jsonObject)
            incoming.put(buildJsonObject {
                put("id", id)
                response.forEach { (key, value) -> put(key, value) }
            }.toString())
        }
        override fun close() { incoming.offer("") }
    }
}
