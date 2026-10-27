package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.StartedRemoteReview
import com.codex.remote.domain.withNewTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRequestTest {
    @Test fun completedReviewCannotBeRevivedByItsDelayedStartReply() = runTest {
        val state = MutableStateFlow(AppUiState(selectedThreadId = "parent"))
        val reply = CompletableDeferred<StartedRemoteReview>()
        var version = 0L
        backgroundScope.launch {
            requestReview(state, { true }, { version }, { reply.await() }, { it.message.orEmpty() })
        }
        runCurrent()
        assertTrue(state.value.isTurnRunning)
        version++
        state.value = state.value.copy(isTurnRunning = false, activeTurnId = null)
        reply.complete(StartedRemoteReview("review-turn", "parent"))
        runCurrent()
        assertFalse(state.value.isTurnRunning)
        assertNull(state.value.activeTurnId)
        assertEquals("parent", state.value.selectedThreadId)
    }

    @Test fun reviewReplyCannotOverwriteANewerTurnEvent() = runTest {
        val state = MutableStateFlow(AppUiState(selectedThreadId = "parent"))
        val reply = CompletableDeferred<StartedRemoteReview>()
        var version = 0L
        backgroundScope.launch {
            requestReview(state, { true }, { version }, { reply.await() }, { it.message.orEmpty() })
        }
        runCurrent()
        version++
        state.value = state.value.copy(isTurnRunning = true, activeTurnId = "new-turn")
        reply.complete(StartedRemoteReview("review-turn", "parent"))
        runCurrent()
        assertEquals("new-turn", state.value.activeTurnId)
    }

    @Test fun reviewResultsStayWithTheirOriginalSelectionAndConnection() = runTest {
        for (navigate in listOf(false, true)) {
            val state = MutableStateFlow(AppUiState(selectedThreadId = "parent"))
            val reply = CompletableDeferred<StartedRemoteReview>()
            var connected = true
            backgroundScope.launch {
                requestReview(state, { connected }, { 0 }, { reply.await() }, { it.message.orEmpty() })
            }
            runCurrent()
            if (navigate) state.value = state.value.withNewTask("/other") else connected = false
            val expected = state.value
            reply.complete(StartedRemoteReview("review-turn", "parent"))
            runCurrent()
            assertEquals(expected, state.value)
        }
    }

    @Test fun normalReviewReplySetsTheTurnAndRejectionClearsIt() = runTest {
        val state = MutableStateFlow(AppUiState(selectedThreadId = "parent"))
        requestReview(state, { true }, { 0 }, { StartedRemoteReview("review-turn", "parent") }, { it.message.orEmpty() })
        assertTrue(state.value.isTurnRunning)
        assertEquals("review-turn", state.value.activeTurnId)
        requestReview(state, { true }, { 0 }, { error("review rejected") }, { it.message.orEmpty() })
        assertFalse(state.value.isTurnRunning)
        assertNull(state.value.activeTurnId)
        assertEquals("review rejected", state.value.notice)
    }
}
