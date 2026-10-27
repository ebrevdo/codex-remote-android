package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Record each notice in the state update, before StateFlow can conflate rapid notifications. */
internal fun MutableStateFlow<AppUiState>.updateWithNotices(transform: (AppUiState) -> AppUiState) {
    update { current -> transform(current).recordNotice() }
}

internal fun AppUiState.recordNotice(): AppUiState {
    val message = notice ?: return this
    if (activeConnection == null || showConnections) return this
    return copy(
        notice = null,
        timeline = timeline + TimelineItem("notice-${UUID.randomUUID()}", TimelineKind.NOTICE, body = message),
    )
}
