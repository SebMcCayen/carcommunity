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
 * - `drives-save` uses requireActiveActor, so a restricted account is refused
 *   permanently. That is not a transient failure and must not be presented
 *   as "try again";
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
 * The result of a successful `drives-save` call — the fields the CLIENT needs to
 * finish the save after the backend has created the drive doc.
 *
 * The callable creates `rides/{rideId}` and computes stats, then returns the
 * canonical Cloud Storage paths but deliberately does NOT write the files (see
 * functions/src/drives/saveDrive.ts and [RouteCodec]'s KDoc): the client uploads
 * the compressed `route.bin` itself. [routePath] is exactly where that upload
 * goes (`rideRoutes/{uid}/{rideId}/route.bin`), taken verbatim from the response
 * so the writer and the server can never disagree about the path.
 *
 * @property routePath the canonical `route.bin` path, or null if the response
 *   omitted it (defensive — the backend always returns it). A null path means
 *   the route upload is skipped rather than sent to a guessed location.
 * @property alreadySaved true when this was an idempotent retry that returned an
 *   existing drive. The route is still re-uploaded on a retry, which is harmless
 *   (same bytes) and recovers a route lost when an earlier attempt's upload
 *   failed after the doc was created.
 */
data class DriveSaveResult(
    val rideId: String,
    val routePath: String?,
    val alreadySaved: Boolean,
)

/**
 * Saved-drives access (Phase 12 slice 12). Firebase-free for testability.
 * Reads are owner-scoped (`rides.userId == uid`, rules-gated); mutations are
 * save (via the active-account `drives-save` callable) and delete (via
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
     * Returns the [DriveSaveResult] carrying the canonical `route.bin` path the
     * client then uploads the compressed route to (the backend creates the doc
     * but not the file).
     *
     * @throws DriveSaveException on any save failure, carrying the callable
     *   status code (null if the failure had none) so callers can separate a
     *   permanent refusal (restricted account access) from a retryable fault.
     */
    suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult

    suspend fun deleteDrive(rideId: String)
}
