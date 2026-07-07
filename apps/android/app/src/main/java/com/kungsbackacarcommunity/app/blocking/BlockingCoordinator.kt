package com.kungsbackacarcommunity.app.blocking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a block/unblock action. */
sealed interface BlockActionStatus {
    data object Idle : BlockActionStatus

    data object Working : BlockActionStatus

    data object Done : BlockActionStatus

    data object Failed : BlockActionStatus
}

/**
 * Orchestrates block/unblock (Phase 12 slice 8). Pure Kotlin so it is
 * unit-testable with a fake repository. The list observer reflects the change;
 * this only tracks a status so the screen can close a confirm dialog on success
 * or surface an error.
 */
class BlockingCoordinator(
    private val repository: BlockingRepository,
) {
    private val state = MutableStateFlow<BlockActionStatus>(BlockActionStatus.Idle)
    val actionStatus: StateFlow<BlockActionStatus> = state.asStateFlow()

    suspend fun block(targetUserId: String) = run { repository.block(targetUserId) }

    suspend fun unblock(targetUserId: String) = run { repository.unblock(targetUserId) }

    private suspend fun run(action: suspend () -> Unit) {
        if (state.value == BlockActionStatus.Working) return
        state.value = BlockActionStatus.Working
        try {
            action()
            state.value = BlockActionStatus.Done
        } catch (cancellation: CancellationException) {
            state.value = BlockActionStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = BlockActionStatus.Failed
        }
    }

    fun reset() {
        state.value = BlockActionStatus.Idle
    }
}
