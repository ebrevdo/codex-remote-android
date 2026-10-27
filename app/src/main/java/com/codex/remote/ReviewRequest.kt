package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.StartedRemoteReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/** A late review/start reply must not undo a turn event or a change of conversation. */
internal suspend fun requestReview(
    state: MutableStateFlow<AppUiState>,
    isCurrentConnection: () -> Boolean,
    turnStateVersion: () -> Long,
    startReview: suspend () -> StartedRemoteReview,
    errorMessage: (Throwable) -> String,
) {
    val request = ConversationRequest(state, isCurrentConnection)
    val version = turnStateVersion()
    request.update { it.copy(isTurnRunning = true, activeTurnId = null, notice = null) }
    try {
        val review = startReview()
        request.update {
            if (turnStateVersion() == version) it.copy(isTurnRunning = true, activeTurnId = review.turnId) else it
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        request.update {
            it.copy(
                isTurnRunning = if (turnStateVersion() == version) false else it.isTurnRunning,
                activeTurnId = if (turnStateVersion() == version) null else it.activeTurnId,
                notice = errorMessage(error),
            )
        }
    }
}
