package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the saved-drives list. */
sealed interface DrivesState {
    data object Loading : DrivesState

    /**
     * The owner query failed.
     *
     * @property code the Firestore status name (`PERMISSION_DENIED`,
     *   `UNAVAILABLE`, …), or null when the failure carried none. Carried so the
     *   auto error report files a stable dedup code rather than free text.
     */
    data class Error(val code: String? = null) : DrivesState

    data class Loaded(val drives: List<SavedDrive>) : DrivesState
}

/**
 * Thrown by [DrivesRepository.saveDrive] for ANY save failure — a backend
 * rejection, but equally a transport/network error or a missing cause.
 *
 * [code] is the callable status name (e.g. Firebase Functions'
 * `PERMISSION_DENIED`), carried out of the Firebase layer so the pure domain can
 * act on it without a Firebase import. It exists for two reasons:
 * - `drives-save` is MEMBER-gated (functions/src/drives/saveDrive.ts uses
 *   requireMemberActor), so a non-member is refused permanently. That is not a
 *   transient failure and must not be presented as "try again";
 * - the auto error report files a stable, fingerprintable code instead of a
 *   free-text message.
 *
 * [code] is null exactly when the failure carried no callable status — a raw
 * network/IO error, say — so a null code means "unclassified", never "refused".
 */
class DriveSaveException(
    val code: String?,
    cause: Throwable? = null,
) : Exception(cause)

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
     *
     * @throws DriveSaveException on any save failure, carrying the callable
     *   status code (null if the failure had none) so callers can separate a
     *   permanent refusal (the member gate) from a retryable fault.
     */
    suspend fun saveDrive(request: Map<String, Any?>)

    suspend fun deleteDrive(rideId: String)
}
