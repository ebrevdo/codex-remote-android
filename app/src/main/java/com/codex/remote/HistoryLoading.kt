package com.codex.remote

import com.codex.remote.data.rpc.CodexRpcClient
import com.codex.remote.data.rpc.HistoryPaginationException
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.RemoteThreadHistoryPage
import com.codex.remote.domain.mergeTimelineHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal val AppUiState.canLoadOlderHistory: Boolean
    get() = connectionStatus == ConnectionStatus.CONNECTED && selectedThreadId != null && !isBusy &&
        hasOlderHistory && olderHistoryCursor != null && !isOlderHistoryLoading

/** Only successful pages consume cursors; transient failures can retry the same page. */
internal fun requestOlderHistory(
    scope: CoroutineScope,
    state: MutableStateFlow<AppUiState>,
    isCurrentConnection: () -> Boolean,
    errorMessage: (Throwable) -> String,
    loadPage: suspend (threadId: String, cursor: String) -> RemoteThreadHistoryPage,
): Job? {
    val snapshot = state.value
    val threadId = snapshot.selectedThreadId ?: return null
    val cursor = snapshot.olderHistoryCursor ?: return null
    if (!snapshot.canLoadOlderHistory || !isCurrentConnection()) return null
    if (cursor in snapshot.consumedHistoryCursors) {
        state.update {
            it.copy(
                olderHistoryCursor = null,
                hasOlderHistory = false,
                olderHistoryError = "The remote host returned a repeated history cursor. Loading has stopped.",
            )
        }
        return null
    }
    fun isCurrent(current: AppUiState): Boolean = isCurrentConnection() &&
        current.selectedThreadId == threadId && current.olderHistoryCursor == cursor

    state.update { it.copy(isOlderHistoryLoading = true, olderHistoryError = null) }
    return scope.launch {
        try {
            val page = loadPage(threadId, cursor)
            val consumed = snapshot.consumedHistoryCursors + cursor
            val nextCursor = CodexRpcClient.checkedNextHistoryCursor(page.nextCursor, consumed)
            state.update { current ->
                if (!isCurrent(current)) current else current.copy(
                    timeline = mergeTimelineHistory(page.timeline, current.timeline),
                    olderHistoryCursor = nextCursor,
                    hasOlderHistory = nextCursor != null,
                    isOlderHistoryLoading = false,
                    olderHistoryError = null,
                    consumedHistoryCursors = consumed,
                )
            }
        } catch (cancelled: CancellationException) {
            state.update { if (isCurrent(it)) it.copy(isOlderHistoryLoading = false) else it }
            throw cancelled
        } catch (error: Exception) {
            val stopped = error is HistoryPaginationException
            state.update { current ->
                if (!isCurrent(current)) current else current.copy(
                    olderHistoryCursor = if (stopped) null else current.olderHistoryCursor,
                    hasOlderHistory = !stopped && current.hasOlderHistory,
                    isOlderHistoryLoading = false,
                    olderHistoryError = errorMessage(error),
                )
            }
        }
    }
}
