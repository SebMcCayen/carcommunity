package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the saved-drives list. */
sealed interface DrivesState {
    data object Loading : DrivesState

    data object Error : DrivesState

    data class Loaded(val drives: List<SavedDrive>) : DrivesState
}

/**
 * Saved-drives access (Phase 12 slice 12). Firebase-free for testability.
 * Reads are owner-scoped (`rides.userId == uid`, rules-gated); mutations are
 * save (via the member-gated `drives-save` callable) and delete (via
 * `drives-delete`).
 */
interface DrivesRepository {
    fun observeDrives(uid: String): Flow<DrivesState>

    /**
     * Saves a recorded drive via the `drives-save` callable. The [request] is
     * the exact callable payload produced by
     * [DriveRecorder.buildSaveRequest]; the backend computes all stats and is
     * idempotent per `sourceSessionId`.
     */
    suspend fun saveDrive(request: Map<String, Any?>)

    suspend fun deleteDrive(rideId: String)
}
