package com.kungsbackacarcommunity.app.drives

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [DrivesRepository] backed by an owner Firestore query on `rides` plus the
 * `drives-delete` callable (europe-west1), Phase 12 slice 12. Guarded
 * ([createIfAvailable]).
 */
class FirebaseDrivesRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : DrivesRepository {

    override fun observeDrives(uid: String): Flow<DrivesState> = callbackFlow {
        val registration =
            firestore
                .collection(RIDES)
                .whereEqualTo(FIELD_USER_ID, uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(DrivesState.Error(code = error.code.name))
                        return@addSnapshotListener
                    }
                    val drives = snapshot?.documents?.mapNotNull { it.toSavedDrive() } ?: emptyList()
                    trySend(DrivesState.Loaded(SavedDrives.sortedForList(drives)))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(SAVE_DRIVE)
                .call(request)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        // The callable returns { rideId, routePath, previewImagePath,
                        // alreadySaved, ...stats }. We only need rideId + routePath
                        // (where the client uploads route.bin) + alreadySaved. A
                        // transport success with a malformed body (no rideId) is NOT
                        // a real save, so mapSaveResult throws rather than resume a
                        // fake success — surfaced here as a save failure.
                        val data = task.result?.getData() as? Map<*, *>
                        try {
                            continuation.resume(mapSaveResult(data))
                        } catch (invalid: DriveSaveException) {
                            continuation.resumeWithException(invalid)
                        }
                    } else {
                        val cause =
                            task.exception
                                ?: IllegalStateException("$SAVE_DRIVE failed without a cause")
                        // Surface the callable's status name to the pure domain:
                        // `drives-save` is member-gated, so PERMISSION_DENIED is a
                        // permanent refusal rather than something to retry.
                        continuation.resumeWithException(
                            DriveSaveException(code = cause.callableStatusCode(), cause = cause),
                        )
                    }
                }
        }

    override suspend fun deleteDrive(rideId: String): Unit =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(DELETE_DRIVE)
                .call(mapOf<String, Any?>("rideId" to rideId))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$DELETE_DRIVE failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val RIDES = "rides"
        private const val FIELD_USER_ID = "userId"
        private const val SAVE_DRIVE = "drives-save"
        private const val DELETE_DRIVE = "drives-delete"

        fun createIfAvailable(context: Context): DrivesRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseDrivesRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

/**
 * The callable status name (`PERMISSION_DENIED`, `UNAVAILABLE`, …) for a failed
 * callable, or null when the failure was not a callable status (e.g. a raw
 * network/IO error). Kept private to this Firebase layer so the status crosses
 * into the domain only as a plain [String].
 */
private fun Throwable.callableStatusCode(): String? =
    (this as? FirebaseFunctionsException)?.code?.name

/**
 * Maps a `drives-save` callable response body to a [DriveSaveResult], or throws
 * [DriveSaveException] when the body carries no usable `rideId`.
 *
 * The callback treats a transport-successful task as a saved drive, but a
 * missing/blank `rideId` means the callable did not actually return a created
 * drive. Defaulting it to `""` (the old behaviour) fake-succeeded the save in the
 * UI while silently SKIPPING the route upload (which needs a non-empty path) and
 * leaving the list to disagree with reality. We fail fast instead so the save
 * surfaces as a failure the UX already handles. [code] is null (unclassified)
 * because the failure is a malformed success, not a callable-status refusal.
 *
 * `routePath` is left nullable on purpose — a null path is a documented,
 * tolerated case ([DriveSaveResult]) that just skips the upload, not a failure.
 * A blank/whitespace-only `routePath` is normalized to null for the same reason:
 * it is not a usable upload target, so it must be treated as "no route to upload"
 * (the tolerated skip) rather than propagated as a non-null path that would send
 * the uploader on a doomed upload and burn its retries/backoff. Note the
 * intentional asymmetry with `rideId`: a blank rideId fails fast (throw), a blank
 * routePath skips (null).
 */
internal fun mapSaveResult(data: Map<*, *>?): DriveSaveResult {
    val rideId =
        (data?.get("rideId") as? String)?.takeIf { it.isNotBlank() }
            ?: throw DriveSaveException(
                code = null,
                cause = IllegalStateException("drives-save returned no rideId"),
            )
    return DriveSaveResult(
        rideId = rideId,
        routePath = (data["routePath"] as? String)?.takeIf { it.isNotBlank() },
        alreadySaved = data["alreadySaved"] as? Boolean ?: false,
    )
}

private fun DocumentSnapshot.toSavedDrive(): SavedDrive? {
    if (!exists()) return null
    val duration = getLong("durationSeconds") ?: return null
    return SavedDrive(
        rideId = id,
        title = getString("title"),
        distanceMeters = getDouble("distanceMeters"),
        durationSeconds = duration,
        averageSpeedMetersPerSecond = getDouble("averageSpeedMetersPerSecond"),
        startedAtMillis = getTimestamp("startedAt")?.toDate()?.time,
        endedAtMillis = getTimestamp("endedAt")?.toDate()?.time,
        createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
        // Both fields are ABSENT on every drive saved before 2026-07 — there is
        // no backfill — which getDouble/getString answer as null. That is the
        // UI's placeholder path (a dash, and the route glyph), not an error.
        maxSpeedMetersPerSecond = getDouble("maxSpeedMetersPerSecond"),
        routeThumbnail = getString("routeThumbnail"),
        // Absent (null) on drives saved before the driven-car field existed and on
        // drives with no car — the History card then shows no round car photo.
        carImagePath = getString("carImagePath"),
    )
}
