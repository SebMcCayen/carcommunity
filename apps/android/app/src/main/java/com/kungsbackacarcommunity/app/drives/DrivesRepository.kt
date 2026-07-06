package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the saved-drives list. */
sealed interface DrivesState {
    data object Loading : DrivesState

    data object Error : DrivesState

    data class Loaded(val drives: List<SavedDrive>) : DrivesState
}

/**
 * Saved-drives access (Phase 12 slice 12, read side). Firebase-free for
 * testability. Reads are owner-scoped (`rides.userId == uid`, rules-gated);
 * the only mutation is delete via the `drives-delete` callable.
 */
interface DrivesRepository {
    fun observeDrives(uid: String): Flow<DrivesState>

    suspend fun deleteDrive(rideId: String)
}
