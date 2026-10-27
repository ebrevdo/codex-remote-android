package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.withNewTask
import com.codex.remote.domain.withThreadCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRequestTest {
    @Test
    fun delayedTaskCreationCannotSelectAnAbandonedDraft() = runTest {
        val state = MutableStateFlow(AppUiState().withNewTask("/srv/app"))
        val request = ConversationRequest(state) { true }
        val response = CompletableDeferred<String>()
        backgroundScope.launch {
            val threadId = response.await()
            request.update { it.copy(selectedThreadId = threadId, isTurnRunning = true, activeTurnId = "old-turn") }
        }
        runCurrent()
        // Both drafts have a null thread ID and the same project path.
        state.value = state.value.withNewTask("/srv/app")
        val replacement = state.value
        response.complete("old-thread")
        runCurrent()

        assertFalse(request.isCurrent())
        assertEquals(replacement, state.value)
    }

    @Test
    fun leavingAndReopeningTheSameThreadRejectsOldResumeResultsAndFailures() {
        val state = MutableStateFlow(AppUiState().withNewTask("/srv/app").copy(selectedThreadId = "thread-a"))
        val request = ConversationRequest(state) { true }
        state.value = state.value.withNewTask("/srv/app").copy(selectedThreadId = "thread-b")
        state.value = state.value.withNewTask("/srv/app").copy(selectedThreadId = "thread-a", isBusy = true)
        val replacement = state.value

        request.update { it.copy(isTurnRunning = true, activeTurnId = "stale-turn") }
        request.update { it.copy(isBusy = false, notice = "Stale resume failed") }

        assertFalse(request.isCurrent())
        assertEquals(replacement, state.value)
    }

    @Test
    fun closingTheConnectionRejectsPendingResultsEvenForTheSameSelection() {
        var connected = true
        val state = MutableStateFlow(AppUiState(selectedThreadId = "thread-a"))
        val request = ConversationRequest(state) { connected }
        connected = false
        val replacement = state.value

        request.update { it.copy(isTurnRunning = true, notice = "Old connection") }

        assertFalse(request.isCurrent())
        assertEquals(replacement, state.value)
    }

    @Test
    fun assigningARemoteIdAndRefreshingTheCatalogKeepsTheSubmissionCurrent() {
        val state = MutableStateFlow(AppUiState().withNewTask("/srv/app"))
        val request = ConversationRequest(state) { true }
        request.update { it.copy(selectedThreadId = "new-thread", isTurnRunning = true) }
        state.value = state.value.withThreadCatalog(emptyList())
        request.update { it.copy(activeTurnId = "new-turn") }

        assertTrue(request.isCurrent())
        assertEquals("new-thread", state.value.selectedThreadId)
        assertEquals("new-turn", state.value.activeTurnId)
        assertTrue(state.value.isTurnRunning)
    }
}
