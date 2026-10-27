package com.codex.remote.domain

/** Leave the previous task's state behind without stopping its remote turn. */
internal fun AppUiState.withNewTask(projectPath: String?): AppUiState = copy(
    conversationRevision = conversationRevision + 1,
    selectedProjectPath = projectPath,
    selectedThreadId = null,
    threadGoal = null,
    isGoalLoading = false,
    goalError = null,
    threadTokenUsage = null,
    timeline = emptyList(),
    olderHistoryCursor = null,
    hasOlderHistory = false,
    isOlderHistoryLoading = false,
    olderHistoryError = null,
    consumedHistoryCursors = emptySet(),
    isBusy = false,
    isTurnRunning = false,
    activeTurnId = null,
    pendingApproval = null,
    notice = null,
    selectedCollaborationMode = defaultCollaborationMode(),
)

/** A catalog response can predate a newly created task or omit an archived open task. */
internal fun AppUiState.withThreadCatalog(updatedThreads: List<RemoteThread>): AppUiState {
    val updatedProjects = groupThreadsByProject(updatedThreads)
    return copy(
        threads = updatedThreads,
        projects = updatedProjects,
        selectedProjectPath = selectedProjectPath ?: updatedProjects.firstOrNull()?.path,
    )
}

internal fun AppUiState.defaultCollaborationMode(): String =
    collaborationModes.firstOrNull { it.mode == "default" }?.mode
        ?: collaborationModes.firstOrNull()?.mode
        ?: "default"

/** Reconnecting to the same task keeps its composer identity; navigation gets a fresh draft. */
internal fun AppUiState.withResumedThread(
    thread: RemoteThread,
    projectPath: String?,
    restoring: Boolean = false,
): AppUiState {
    val resumed = withNewTask(projectPath)
    return resumed.copy(
        conversationRevision = if (restoring && selectedThreadId == thread.id) conversationRevision else resumed.conversationRevision,
        selectedThreadId = thread.id,
        timeline = if (restoring && selectedThreadId == thread.id) timeline.filter { it.kind == TimelineKind.NOTICE } else resumed.timeline,
        isGoalLoading = true,
        isBusy = true,
    )
}
