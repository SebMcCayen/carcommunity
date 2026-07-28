package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Outcome of ONE live-location command, returned to the caller.
 *
 * [LiveActionStatus] describes what the CONTROLS should render; this describes
 * what a single call did, which is what the optimistic-start overlay
 * ([com.kungsbackacarcommunity.app.live.LiveShareStart]) needs in order to decide
 * between "wait for the session to echo back" and "revert to the + sign now".
 * Reading the status flow after the call could not answer that: it is shared by
 * every command.
 */
enum class LiveCommandResult {
    /** The callable returned without error. */
    Success,

    /** The callable failed; nothing was started/stopped. */
    Failed,

    /** Another command was already in flight, so this one was not issued. */
    Busy,
}

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

    /**
     * Starts a session. Returns the command's [LiveCommandResult] so the caller
     * can resolve its optimistic "starting…" UI (see [LiveShareStart]) instead of
     * having to guess from the shared [status] flow.
     */
    suspend fun start(duration: LiveSessionDuration): LiveCommandResult =
        execute { repository.startSession(duration) }

    suspend fun stop() = execute { repository.stopSession() }

    /** Extends the active session by a fresh capped window (the "keep sharing" reply). */
    suspend fun extend() = execute { repository.extendSession() }

    /** Privacy stop — always offered to the user (works while suspended too). */
    suspend fun hideMeNow() = execute { repository.hideMeNow() }

    /** Clears a failure so the controls are usable again. */
    fun reset() {
        if (state.value == LiveActionStatus.Failed) {
            state.value = LiveActionStatus.Idle
        }
    }

    private suspend fun execute(action: suspend () -> Unit): LiveCommandResult {
        if (state.value == LiveActionStatus.Working) return LiveCommandResult.Busy
        state.value = LiveActionStatus.Working
        return try {
            action()
            state.value = LiveActionStatus.Idle
            LiveCommandResult.Success
        } catch (cancellation: CancellationException) {
            // Never swallow coroutine cancellation (matches the other coordinators).
            state.value = LiveActionStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            // Details may reference the request payload — never logged.
            state.value = LiveActionStatus.Failed
            LiveCommandResult.Failed
        }
    }
}
