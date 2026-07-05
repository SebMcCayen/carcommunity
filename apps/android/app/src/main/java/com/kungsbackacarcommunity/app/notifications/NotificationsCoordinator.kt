package com.kungsbackacarcommunity.app.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a mark-read action. */
sealed interface MarkReadStatus {
    data object Idle : MarkReadStatus

    data object Working : MarkReadStatus

    data object Failed : MarkReadStatus
}

/**
 * Orchestrates mark-read / mark-all-read (Phase 12 slice 21). Pure Kotlin so it
 * is unit-testable with a fake repository. The inbox list is driven by the
 * repository observer; this only tracks the in-flight action.
 */
class NotificationsCoordinator(
    private val repository: NotificationsRepository,
) {
    private val state = MutableStateFlow<MarkReadStatus>(MarkReadStatus.Idle)
    val status: StateFlow<MarkReadStatus> = state.asStateFlow()

    suspend fun markRead(notificationId: String) = execute { repository.markRead(notificationId) }

    suspend fun markAllRead() = execute { repository.markAllRead() }

    fun reset() {
        if (state.value == MarkReadStatus.Failed) state.value = MarkReadStatus.Idle
    }

    private suspend fun execute(action: suspend () -> Unit) {
        if (state.value == MarkReadStatus.Working) return
        state.value = MarkReadStatus.Working
        try {
            action()
            state.value = MarkReadStatus.Idle
        } catch (cancellation: CancellationException) {
            state.value = MarkReadStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = MarkReadStatus.Failed
        }
    }
}
