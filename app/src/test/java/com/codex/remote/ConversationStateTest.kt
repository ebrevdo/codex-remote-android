package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.RemoteAccount
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.RemoteThreadTokenUsage
import com.codex.remote.domain.ThreadGoal
import com.codex.remote.domain.ThreadGoalStatus
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import com.codex.remote.domain.withNewTask
import com.codex.remote.domain.withThreadArchived
import com.codex.remote.domain.withThreadCatalog
import org.junit.Assert.*
import org.junit.Test

class ConversationStateTest {
    @Test
    fun newTaskClearsRunningAndRestoringStateInTheSameOrAnotherProject() {
        for (turnId in listOf("turn-a", null)) {
            for (projectPath in listOf("/srv/app", "/srv/other")) {
                val running = runningState().copy(activeTurnId = turnId)
                val draft = running.withNewTask(projectPath)

                assertEquals(projectPath, draft.selectedProjectPath)
                assertNull(draft.selectedThreadId)
                assertFalse(draft.isTurnRunning)
                assertNull(draft.activeTurnId)
                assertFalse(draft.isBusy)
                assertNull(draft.pendingApproval)
                assertNull(draft.notice)
                assertTrue(draft.timeline.isEmpty())
                assertNull(draft.threadGoal)
                assertFalse(draft.isGoalLoading)
                assertNull(draft.goalError)
                assertNull(draft.threadTokenUsage)
                assertNull(draft.olderHistoryCursor)
                assertFalse(draft.hasOlderHistory)
                assertFalse(draft.isOlderHistoryLoading)
                assertNull(draft.olderHistoryError)
                assertTrue(draft.consumedHistoryCursors.isEmpty())
                assertFalse(draft.acceptsThreadEvent("thread-a"))
                assertEquals("default", draft.selectedCollaborationMode)
                // New-task navigation must not stop the remote task or reset host preferences.
                assertEquals(running.threads, draft.threads)
                assertEquals("active", draft.threads.single().status)
                assertEquals(running.remoteAccount, draft.remoteAccount)
                assertEquals(running.connectionStatus, draft.connectionStatus)
                assertEquals(running.selectedModel, draft.selectedModel)
                assertEquals(running.selectedReasoningEffort, draft.selectedReasoningEffort)
                assertEquals(running.approvalsReviewer, draft.approvalsReviewer)
                assertEquals(running.selectedPermissionProfile, draft.selectedPermissionProfile)
            }
        }
    }

    @Test
    fun staleCatalogCannotRemoveAnOpenTaskOrItsLiveEvents() {
        val openTask = runningState()
        val refreshed = openTask.withThreadCatalog(emptyList())

        assertTrue(refreshed.threads.isEmpty())
        assertTrue(refreshed.projects.isEmpty())
        assertEquals(openTask.copy(threads = emptyList(), projects = emptyList()), refreshed)
        assertTrue(refreshed.acceptsThreadEvent("thread-a"))
    }

    @Test
    fun catalogCannotResetAnOptimisticSubmissionOrSwitchItsProject() {
        val submitting = runningState().withNewTask("/srv/new-project").copy(
            isTurnRunning = true,
            isGoalLoading = true,
            timeline = listOf(TimelineItem("local-message", TimelineKind.USER, body = "Build it")),
        )
        val refreshed = submitting.withThreadCatalog(listOf(thread("other", "/srv/other")))

        assertEquals("/srv/new-project", refreshed.selectedProjectPath)
        assertNull(refreshed.selectedThreadId)
        assertTrue(refreshed.isTurnRunning)
        assertTrue(refreshed.isGoalLoading)
        assertEquals(submitting.timeline, refreshed.timeline)
        assertEquals(submitting.conversationRevision, refreshed.conversationRevision)
        assertEquals("/srv/other", refreshed.projects.single().path)
    }

    @Test
    fun firstCatalogChoosesAProjectWhenNothingIsSelected() {
        val refreshed = AppUiState().withThreadCatalog(listOf(thread("first", "/srv/app")))
        assertEquals("/srv/app", refreshed.selectedProjectPath)
        assertNull(refreshed.selectedThreadId)
    }

    @Test
    fun archivingTheOpenTaskResetsItButArchivingAnotherTaskKeepsIt() {
        val current = runningState().withThreadCatalog(listOf(thread("thread-a"), thread("thread-b")))
        val otherArchived = current.withThreadArchived("thread-b")
        assertEquals(current.withThreadCatalog(listOf(thread("thread-a"))), otherArchived)

        val selectedArchived = current.withThreadArchived("thread-a")
        assertNull(selectedArchived.selectedThreadId)
        assertFalse(selectedArchived.isTurnRunning)
        assertFalse(selectedArchived.isBusy)
        assertNull(selectedArchived.activeTurnId)
        assertNull(selectedArchived.pendingApproval)
        assertEquals(listOf("thread-b"), selectedArchived.threads.map { it.id })
    }

    private fun runningState() = AppUiState(
        selectedProjectPath = "/srv/app",
        selectedThreadId = "thread-a",
        threads = listOf(thread("thread-a")),
        connectionStatus = ConnectionStatus.CONNECTED,
        remoteAccount = RemoteAccount("chatgpt", null, "plus", false),
        selectedModel = "test-model",
        selectedReasoningEffort = "high",
        selectedPermissionProfile = ":workspace",
        approvalsReviewer = "auto_review",
        selectedCollaborationMode = "plan",
        isTurnRunning = true,
        activeTurnId = "turn-a",
        isBusy = true,
        notice = "Restoring the running task",
        pendingApproval = ApprovalRequest("approval", ApprovalKind.COMMAND, "Run", "test", "approval"),
        timeline = listOf(TimelineItem("working", TimelineKind.AGENT, body = "Working")),
        threadGoal = ThreadGoal("thread-a", "Ship", ThreadGoalStatus.ACTIVE, null, 1, 1, 1, 1),
        isGoalLoading = true,
        goalError = "Previous goal error",
        threadTokenUsage = RemoteThreadTokenUsage(100, 80, 20, 1000),
        olderHistoryCursor = "cursor",
        hasOlderHistory = true,
        isOlderHistoryLoading = true,
        olderHistoryError = "Previous history error",
        consumedHistoryCursors = setOf("previous"),
    )

    private fun thread(id: String, cwd: String = "/srv/app") = RemoteThread(id, id, cwd, 1, "active")
}
