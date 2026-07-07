package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a delete. */
sealed interface DriveDeleteStatus {
    data object Idle : DriveDeleteStatus

    data object Deleting : DriveDeleteStatus

    data object Deleted : DriveDeleteStatus

    data object Failed : DriveDeleteStatus
}

/**
 * Orchestrates saved-drive deletion (Phase 12 slice 12). Pure Kotlin so it is
 * unit-testable with a fake repository. The list observer reflects the removal;
 * this only tracks a status so the detail view can close on success or surface
 * an error.
 */
class DrivesCoordinator(
    private val repository: DrivesRepository,
) {
    private val state = MutableStateFlow<DriveDeleteStatus>(DriveDeleteStatus.Idle)
    val deleteStatus: StateFlow<DriveDeleteStatus> = state.asStateFlow()

    suspend fun delete(rideId: String) {
        if (state.value == DriveDeleteStatus.Deleting) return
        state.value = DriveDeleteStatus.Deleting
        try {
            repository.deleteDrive(rideId)
            state.value = DriveDeleteStatus.Deleted
        } catch (cancellation: CancellationException) {
            state.value = DriveDeleteStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = DriveDeleteStatus.Failed
        }
    }

    fun reset() {
        state.value = DriveDeleteStatus.Idle
    }
}
