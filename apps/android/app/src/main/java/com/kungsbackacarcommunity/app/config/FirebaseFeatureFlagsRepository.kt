package com.kungsbackacarcommunity.app.config

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [FeatureFlagsRepository] backed by the single flat config/featureFlags
 * Firestore document (Phase 9m). Authenticated read; a one-shot get() per
 * refresh (no realtime listener — poll-on-resume is enough for MVP, per the
 * mapping). Construction is guarded ([createIfAvailable] returns null when
 * Firebase is not configured).
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

    companion object {
        private const val CONFIG = "config"
        private const val FEATURE_FLAGS = "featureFlags"

        fun createIfAvailable(context: Context): FeatureFlagsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseFeatureFlagsRepository(FirebaseFirestore.getInstance())
        }
    }
}
