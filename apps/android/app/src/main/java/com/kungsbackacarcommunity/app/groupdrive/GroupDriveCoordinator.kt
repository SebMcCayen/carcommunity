package com.kungsbackacarcommunity.app.groupdrive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of an in-flight group-drive command. */
sealed interface GroupDriveActionStatus {
    data object Idle : GroupDriveActionStatus

    data object Working : GroupDriveActionStatus

    data object Failed : GroupDriveActionStatus
}

/**
 * Orchestrates join / updateStatus / leave (Phase 12 slice 11). Pure Kotlin so
 * it is unit-testable with a fake repository. The roster + own-status flows
 * drive the UI; this only tracks the in-flight command.
 */
class GroupDriveCoordinator(
    private val repository: GroupDriveRepository,
) {
    private val state = MutableStateFlow<GroupDriveActionStatus>(GroupDriveActionStatus.Idle)
    val status: StateFlow<GroupDriveActionStatus> = state.asStateFlow()

    suspend fun join(eventId: String) = execute { repository.join(eventId) }

    suspend fun updateStatus(eventId: String, status: GroupDriveStatus) =
        execute { repository.updateStatus(eventId, status) }

    suspend fun leave(eventId: String) = execute { repository.leave(eventId) }

    fun reset() {
        if (state.value == GroupDriveActionStatus.Failed) state.value = GroupDriveActionStatus.Idle
    }

    private suspend fun execute(action: suspend () -> Unit) {
        if (state.value == GroupDriveActionStatus.Working) return
        state.value = GroupDriveActionStatus.Working
        try {
            action()
            state.value = GroupDriveActionStatus.Idle
        } catch (cancellation: CancellationException) {
            state.value = GroupDriveActionStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = GroupDriveActionStatus.Failed
        }
    }
}
