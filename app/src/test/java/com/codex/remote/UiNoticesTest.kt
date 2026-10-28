package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.SavedConnection
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import com.codex.remote.domain.withNewTask
import com.codex.remote.domain.withResumedThread
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class UiNoticesTest {
    private val connection = SavedConnection("host", "Host", "example.com", username = "user", authType = AuthType.PASSWORD)

    @Test
    fun routineSocketAbortMessagesNeverBecomeChatOrSettingsNotices() {
        for (settings in listOf(false, true)) {
            val state = MutableStateFlow(AppUiState(activeConnection = connection, showConnections = settings))
            for (message in listOf(
                "Software caused connection abort",
                "java.net.SocketException: Software caused connection abort: recv failed",
                "SOFTWARE CAUSED DISCONNECTION",
                "Software created disconnection",
            )) {
                state.updateWithNotices { it.copy(notice = message) }
            }
            assertNull(state.value.notice)
            assertTrue(state.value.timeline.isEmpty())
        }
    }

    @Test
    fun filteringTransportChatterKeepsConnectionStatusAndConversationContent() {
        val socketError = "Software caused connection abort"
        val answer = TimelineItem("answer", TimelineKind.AGENT, body = "The log says: $socketError")
        val state = AppUiState(
            activeConnection = connection,
            connectionStatus = ConnectionStatus.CONNECTING,
            connectionMessage = "Disconnected. Reconnecting…",
            statusError = socketError,
            timeline = listOf(answer),
            notice = socketError,
        ).recordNotice()
        assertEquals(ConnectionStatus.CONNECTING, state.connectionStatus)
        assertEquals("Disconnected. Reconnecting…", state.connectionMessage)
        assertEquals(socketError, state.statusError)
        assertEquals(listOf(answer), state.timeline)
        assertNull(state.notice)
    }

    @Test
    fun actionableErrorsAndApprovalMessagesStillAppear() {
        val state = MutableStateFlow(AppUiState(activeConnection = connection))
        val messages = listOf("SSH host key changed", "SSH authentication failed", "Action approved", "Download failed: disk full")
        messages.forEach { message -> state.updateWithNotices { it.copy(notice = message) } }
        assertEquals(messages, state.value.timeline.map { it.body })
    }

    @Test
    fun rapidAndIdenticalNoticesRemainInOrderWithoutWaitingForTheUi() {
        val state = MutableStateFlow(AppUiState(activeConnection = connection))
        val messages = listOf("Reviewing approval", "Action approved", "Action approved")
        messages.forEach { message -> state.updateWithNotices { it.copy(notice = message) } }
        state.updateWithNotices { it.copy(isBusy = true) }
        assertNull(state.value.notice)
        assertEquals(messages, state.value.timeline.map { it.body })
        assertTrue(state.value.timeline.all { it.kind == TimelineKind.NOTICE })
        assertEquals(3, state.value.timeline.map { it.id }.distinct().size)
    }

    @Test
    fun pendingOperationNoticesAreRecordedButStaleResultsAreRejected() {
        val state = MutableStateFlow(AppUiState(activeConnection = connection))
        val request = ConversationRequest(state) { true }
        request.update { it.copy(notice = "Message added to the running task") }
        assertEquals("Message added to the running task", state.value.timeline.single().body)
        state.value = state.value.withNewTask("/repo")
        request.update { it.copy(notice = "Old task error") }
        assertTrue(state.value.timeline.isEmpty())
    }

    @Test
    fun restoringTheSameTaskRetainsNoticesWhileNavigationClearsThem() {
        val thread = RemoteThread("task", "Task", "/repo", 1, "idle")
        val original = AppUiState(activeConnection = connection, selectedThreadId = thread.id)
            .copy(notice = "Action approved").recordNotice()
        val resumed = original.withResumedThread(thread, "/repo", restoring = true)
        assertEquals(original.timeline, resumed.timeline)
        assertTrue(original.withResumedThread(thread, "/repo").timeline.isEmpty())
        assertTrue(original.withNewTask("/repo").timeline.isEmpty())
    }

    @Test
    fun newNoticesAppendAfterMessagesAndSurviveLoadingOlderHistory() {
        val message = TimelineItem("answer", TimelineKind.AGENT, body = "Answer")
        val state = AppUiState(activeConnection = connection, timeline = listOf(message), notice = "Warning").recordNotice()
        val merged = com.codex.remote.domain.mergeTimelineHistory(listOf(message), state.timeline)
        assertEquals(state.timeline, merged)
        assertEquals(listOf(TimelineKind.AGENT, TimelineKind.NOTICE), merged.map { it.kind })
    }

    @Test
    fun hostSettingsNoticesRemainInlineUntilDismissedOrReturningToTheWorkspace() {
        val disconnected = AppUiState(notice = "Could not save host")
        assertEquals(disconnected, disconnected.recordNotice())
        val settings = disconnected.copy(activeConnection = connection, showConnections = true)
        assertEquals(settings, settings.recordNotice())
        val workspace = settings.copy(showConnections = false).recordNotice()
        assertNull(workspace.notice)
        assertEquals("Could not save host", workspace.timeline.single().body)
    }
}
