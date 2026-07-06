package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of an in-flight live-location command (start/stop/hide). */
sealed interface LiveActionStatus {
    data object Idle : LiveActionStatus

    data object Working : LiveActionStatus

    data object Failed : LiveActionStatus
}

/**
 * Orchestrates the live-location session commands (Phase 12 slice 5). Pure
 * Kotlin (no Firebase/Android types) so the flow is unit-testable with a fake
 * repository.
 *
 * The observed session ([LiveLocationRepository.observeOwnSession]) is what
 * decides whether the UI shows "sharing"; this coordinator only tracks the
 * command currently in flight so the buttons can show progress and surface a
 * failure without swallowing coroutine cancellation.
 */
class LiveLocationCoordinator(
    private val repository: LiveLocationRepository,
) {
    private val state = MutableStateFlow<LiveActionStatus>(LiveActionStatus.Idle)
    val status: StateFlow<LiveActionStatus> = state.asStateFlow()

    suspend fun start(duration: LiveSessionDuration) = execute { repository.startSession(duration) }

    suspend fun stop() = execute { repository.stopSession() }

    /** Privacy stop — always offered to the user (works while suspended too). */
    suspend fun hideMeNow() = execute { repository.hideMeNow() }

    /** Clears a failure so the controls are usable again. */
    fun reset() {
        if (state.value == LiveActionStatus.Failed) {
            state.value = LiveActionStatus.Idle
        }
    }

    private suspend fun execute(action: suspend () -> Unit) {
        if (state.value == LiveActionStatus.Working) return
        state.value = LiveActionStatus.Working
        try {
            action()
            state.value = LiveActionStatus.Idle
        } catch (cancellation: CancellationException) {
            // Never swallow coroutine cancellation (matches the other coordinators).
            state.value = LiveActionStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            // Details may reference the request payload — never logged.
            state.value = LiveActionStatus.Failed
        }
    }
}
