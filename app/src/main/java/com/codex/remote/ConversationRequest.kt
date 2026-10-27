package com.codex.remote

import com.codex.remote.domain.AppUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** Keep asynchronous results attached to the selection that initiated them. */
internal class ConversationRequest(
    private val state: MutableStateFlow<AppUiState>,
    private val isCurrentConnection: () -> Boolean,
) {
    private val revision = state.value.conversationRevision

    fun isCurrent(): Boolean = isCurrentConnection() && state.value.conversationRevision == revision

    fun update(transform: (AppUiState) -> AppUiState) {
        state.updateWithNotices { current ->
            if (isCurrentConnection() && current.conversationRevision == revision) transform(current) else current
        }
    }
}
