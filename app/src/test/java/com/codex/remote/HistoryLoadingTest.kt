package com.codex.remote

import com.codex.remote.data.rpc.RpcException
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.RemoteThreadHistoryPage
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryLoadingTest {
    @Test fun failedPageCanRetryTheSameCursorAndKeepLiveMessages() = runTest {
        val state = MutableStateFlow(historyState())
        val requests = mutableListOf<String>()
        val response = CompletableDeferred<RemoteThreadHistoryPage>()
        val fetch: suspend (String, String) -> RemoteThreadHistoryPage = { threadId, cursor ->
            assertEquals("thread-a", threadId)
            requests += cursor
            if (requests.size == 1) throw RpcException("Temporary history error")
            response.await()
        }
        requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }, fetch)
        runCurrent()
        assertEquals("Temporary history error", state.value.olderHistoryError)
        assertTrue(state.value.canLoadOlderHistory)
        assertTrue(state.value.consumedHistoryCursors.isEmpty())
        requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }, fetch)
        runCurrent()
        state.value = state.value.copy(timeline = state.value.timeline + item("live"))
        response.complete(RemoteThreadHistoryPage(listOf(item("older")), "page-3"))
        runCurrent()
        assertEquals(listOf("page-2", "page-2"), requests)
        assertEquals(listOf("older", "current", "live"), state.value.timeline.map { it.id })
        assertEquals(setOf("page-2"), state.value.consumedHistoryCursors)
        assertEquals("page-3", state.value.olderHistoryCursor)
        assertNull(state.value.olderHistoryError)
    }

    @Test fun emptyPageAdvancesAndTheNextRequestStillLoadsHistory() = runTest {
        val state = MutableStateFlow(historyState())
        requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }) { _, cursor ->
            assertEquals("page-2", cursor)
            RemoteThreadHistoryPage(emptyList(), "page-3")
        }
        runCurrent()
        assertTrue(state.value.canLoadOlderHistory)
        assertEquals(listOf(item("current")), state.value.timeline)
        requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }) { _, cursor ->
            assertEquals("page-3", cursor)
            RemoteThreadHistoryPage(listOf(item("older")), null)
        }
        runCurrent()
        assertEquals(listOf(item("older"), item("current")), state.value.timeline)
        assertFalse(state.value.hasOlderHistory)
    }

    @Test fun duplicateClicksDoNotStartAnotherRequest() = runTest {
        val state = MutableStateFlow(historyState())
        val response = CompletableDeferred<RemoteThreadHistoryPage>()
        var calls = 0
        val fetch: suspend (String, String) -> RemoteThreadHistoryPage = { _, _ ->
            calls++
            response.await()
        }
        requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }, fetch)
        assertNull(requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }, fetch))
        runCurrent()
        assertEquals(1, calls)
        response.complete(RemoteThreadHistoryPage(emptyList(), null))
        runCurrent()
    }

    @Test fun oldConnectionsAndThreadsCannotOverwriteCurrentHistory() = runTest {
        for (switchConnection in listOf(true, false)) {
            val state = MutableStateFlow(historyState())
            var currentConnection = true
            val response = CompletableDeferred<RemoteThreadHistoryPage>()
            requestOlderHistory(backgroundScope, state, { currentConnection }, { it.message.orEmpty() }) { _, _ -> response.await() }
            runCurrent()
            currentConnection = !switchConnection
            val replacement = historyState().copy(selectedThreadId = if (switchConnection) "thread-a" else "thread-b")
            state.value = replacement
            response.complete(RemoteThreadHistoryPage(listOf(item("stale")), null))
            runCurrent()
            assertEquals(replacement, state.value)
        }
    }

    @Test fun cancellationLeavesThePageRetryable() = runTest {
        val state = MutableStateFlow(historyState())
        val response = CompletableDeferred<RemoteThreadHistoryPage>()
        val job = requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }) { _, _ -> response.await() }!!
        runCurrent()
        job.cancelAndJoin()
        assertTrue(state.value.canLoadOlderHistory)
        assertTrue(state.value.consumedHistoryCursors.isEmpty())
        assertNull(state.value.olderHistoryError)
    }

    @Test fun cursorCyclesStopWithAnExplicitError() = runTest {
        val state = MutableStateFlow(historyState().copy(consumedHistoryCursors = setOf("page-1")))
        requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }) { _, _ ->
            RemoteThreadHistoryPage(emptyList(), "page-1")
        }
        runCurrent()
        assertFalse(state.value.hasOlderHistory)
        assertFalse(state.value.isOlderHistoryLoading)
        assertNull(state.value.olderHistoryCursor)
        assertTrue(state.value.olderHistoryError.orEmpty().contains("repeated history cursor"))
    }

    @Test fun busyOrDisconnectedViewsDoNotStartHistoryRequests() = runTest {
        for (initial in listOf(
            historyState().copy(isBusy = true),
            historyState().copy(connectionStatus = ConnectionStatus.CONNECTING),
            historyState().copy(selectedThreadId = null),
        )) {
            val state = MutableStateFlow(initial)
            assertNull(requestOlderHistory(backgroundScope, state, { true }, { it.message.orEmpty() }) { _, _ ->
                error("Unexpected request")
            })
            assertEquals(initial, state.value)
        }
    }

    private fun historyState() = AppUiState(
        connectionStatus = ConnectionStatus.CONNECTED,
        selectedThreadId = "thread-a",
        timeline = listOf(item("current")),
        hasOlderHistory = true,
        olderHistoryCursor = "page-2",
    )

    private fun item(id: String) = TimelineItem(id, TimelineKind.AGENT, body = id)
}
