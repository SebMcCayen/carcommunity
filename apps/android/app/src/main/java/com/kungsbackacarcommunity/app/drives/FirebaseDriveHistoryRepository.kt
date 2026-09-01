package com.kungsbackacarcommunity.app.drives

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [DriveHistoryRepository] backed by the `drives-listHistory` and `drives-stats`
 * callables (europe-west1, App Check enforced server-side). Guarded
 * ([createIfAvailable]) so a config-less / CI build gets a null repository and the
 * History tab renders its no-backend fallback. Mirrors the region/error
 * conventions of the other callable repositories (WaveRepository,
 * FirebaseUserSearchRepository, FirebaseGroupDriveRepository).
 */
class FirebaseDriveHistoryRepository private constructor(
    private val functions: FirebaseFunctions,
) : DriveHistoryRepository {

    override suspend fun listHistory(cursorRideId: String?, pageSize: Int?): DriveHistoryPage {
        val payload = buildMap<String, Any?> {
            cursorRideId?.takeIf { it.isNotBlank() }?.let { put("cursorRideId", it) }
            pageSize?.let { put("pageSize", it) }
        }
        val data = callForData(LIST_HISTORY, payload)
        return DriveHistoryMapper.pageFromWire(data)
    }

    override suspend fun fetchStats(monthStartMillis: Long?, monthEndMillis: Long?): DriveStatsSnapshot {
        val payload = buildMap<String, Any?> {
            // Both-or-neither: the callable rejects one without the other, so never
            // send a lone bound.
            if (monthStartMillis != null && monthEndMillis != null) {
                put("monthStartMillis", monthStartMillis)
                put("monthEndMillis", monthEndMillis)
            }
        }
        val data = callForData(DRIVE_STATS, payload)
        return DriveHistoryMapper.statsFromWire(data)
    }

    /**
     * Invokes [name] and returns its payload map, translating any failure into a
     * [DriveHistoryException] carrying the callable status name. A 2xx with no Map
     * body is a protocol fault (unclassified code), not an empty answer.
     * CancellationException propagates untouched so a cancelled load is a real
     * cancellation.
     */
    private suspend fun callForData(name: String, payload: Map<String, Any?>): Map<*, *> =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(payload)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        val data = task.result?.getData() as? Map<*, *>
                        if (data == null) {
                            continuation.resumeWithException(
                                DriveHistoryException(
                                    code = null,
                                    cause = IllegalStateException("$name returned an unexpected or empty payload"),
                                ),
                            )
                        } else {
                            continuation.resume(data)
                        }
                    } else {
                        val cause =
                            task.exception ?: IllegalStateException("$name failed without a cause")
                        continuation.resumeWithException(
                            DriveHistoryException(code = cause.callableStatusCode(), cause = cause),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val LIST_HISTORY = "drives-listHistory"
        private const val DRIVE_STATS = "drives-stats"

        fun createIfAvailable(context: Context): DriveHistoryRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseDriveHistoryRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

/**
 * The callable status name (`PERMISSION_DENIED`, `UNAVAILABLE`, …) for a failed
 * callable, or null when the failure was not a callable status (a raw network/IO
 * error). Kept private to this Firebase layer so the status crosses into the
 * domain only as a plain [String].
 */
private fun Throwable.callableStatusCode(): String? =
    (this as? FirebaseFunctionsException)?.code?.name
