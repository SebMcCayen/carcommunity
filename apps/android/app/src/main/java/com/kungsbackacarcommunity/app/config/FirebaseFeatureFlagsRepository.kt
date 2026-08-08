package com.kungsbackacarcommunity.app.config

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [FeatureFlagsRepository] backed by the single flat config/featureFlags
 * Firestore document (Phase 9m). Authenticated read.
 *
 * [observe] is the durable mechanism: a realtime snapshot listener that
 * auto-reconnects, so the real backend value is delivered as soon as an
 * authenticated read succeeds — a transient startup failure no longer strands
 * a client on the conservative defaults. [fetch] remains as a one-shot fast
 * path (poll-on-resume). Construction is guarded ([createIfAvailable] returns
 * null when Firebase is not configured).
 */
class FirebaseFeatureFlagsRepository private constructor(
    private val firestore: FirebaseFirestore,
) : FeatureFlagsRepository {

    override suspend fun fetch(): FeatureFlags =
        suspendCancellableCoroutine { continuation ->
            firestore
                .collection(CONFIG)
                .document(FEATURE_FLAGS)
                .get()
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        val stored = task.result?.data ?: emptyMap()
                        continuation.resume(FeatureFlags.fromStored(stored))
                    } else {
                        continuation.resumeWithException(
                            task.exception
                                ?: IllegalStateException("Feature flags read failed without a cause"),
                        )
                    }
                }
        }

    override fun observe(): Flow<FeatureFlags> = callbackFlow {
        val registration =
            firestore
                .collection(CONFIG)
                .document(FEATURE_FLAGS)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // A listener error (e.g. a transient permission race at
                        // sign-in, or a dropped connection) must NOT reset the
                        // flags to defaults: emit nothing and let the collector
                        // keep the last good value. Firestore reconnects on its
                        // own and re-delivers the current snapshot when it can.
                        return@addSnapshotListener
                    }
                    // A null/absent document overlays nothing onto the defaults;
                    // fromStored(emptyMap()) is the documented degrade path.
                    val stored = snapshot?.data ?: emptyMap()
                    trySend(FeatureFlags.fromStored(stored))
                }
        awaitClose { registration.remove() }
    }

    companion object {
        private const val CONFIG = "config"
        private const val FEATURE_FLAGS = "featureFlags"

        fun createIfAvailable(context: Context): FeatureFlagsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseFeatureFlagsRepository(FirebaseFirestore.getInstance())
        }
    }
}
