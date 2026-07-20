package com.kungsbackacarcommunity.app.drives

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [RouteReplayRepository] backed by Cloud Storage: downloads
 * `rideRoutes/{uid}/{rideId}/route.bin` with `getBytes` (the same owner +
 * member-gated prefix the storage rules protect — the authenticated Firebase
 * user IS the owner, so the read is authorised exactly like every other
 * member-gated read), then decodes it with [RouteCodec]. Guarded construction
 * ([createIfAvailable] returns null with no Firebase), so the config-less / CI
 * build carries no reader and the detail screen degrades to a placeholder.
 *
 * ## Caching
 * A successful decode is cached IN MEMORY, keyed by uid+rideId, so re-opening a
 * drive during the session redraws instantly without a second download. Per the
 * task this is deliberately memory-only — no disk cache (Coil is for images;
 * this is binary). Failures are NOT cached, so a transient network/permission
 * error retries on the next open rather than sticking.
 */
class FirebaseRouteReplayRepository private constructor(
    private val storage: FirebaseStorage,
) : RouteReplayRepository {

    private val cache = ConcurrentHashMap<String, List<RoutePoint>>()

    override suspend fun loadRoute(uid: String, rideId: String): RouteReplayState {
        val key = "$uid/$rideId"
        cache[key]?.let { return RouteReplayState.Ready(it) }

        val path = routeBinPath(uid, rideId)
        val bytes =
            runCatching { downloadBytes(path) }.getOrNull()
                ?: return RouteReplayState.Unavailable

        val points = RouteCodec.decode(bytes) ?: return RouteReplayState.Unavailable
        cache[key] = points
        return RouteReplayState.Ready(points)
    }

    private suspend fun downloadBytes(path: String): ByteArray =
        suspendCancellableCoroutine { continuation ->
            // getBytes returns a plain Task<ByteArray> (not a cancelable
            // StorageTask), so there is nothing to cancel on the download itself;
            // the isActive guards keep a resume after cancellation harmless.
            storage.reference.child(path).getBytes(MAX_ROUTE_BYTES)
                .addOnSuccessListener { bytes ->
                    if (continuation.isActive) continuation.resume(bytes)
                }
                .addOnFailureListener { error ->
                    // A missing file, a denied read, or a network fault all land
                    // here; the caller maps the resulting failure to Unavailable.
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
        }

    companion object {
        /** Canonical member-gated route file path (mirrors rideRoutePath in functions/). */
        private fun routeBinPath(uid: String, rideId: String): String =
            "rideRoutes/$uid/$rideId/route.bin"

        /**
         * Hard cap on the downloaded route file. Well above a real route (20 000
         * points ≈ 140 KB raw, less gzipped) yet bounded, so a corrupt/oversized
         * object fails the download instead of allocating unbounded memory.
         */
        private const val MAX_ROUTE_BYTES: Long = 16L * 1024 * 1024

        fun createIfAvailable(context: Context): RouteReplayRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseRouteReplayRepository(FirebaseStorage.getInstance())
        }
    }
}
