package com.kungsbackacarcommunity.app.update

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [AppVersionConfigRepository] backed by the flat `config/appVersion`
 * Firestore document, alongside `config/featureFlags` — the same
 * backend-managed config channel, the same one-shot `get()` (no realtime
 * listener; an update prompt does not need to arrive mid-session), and the
 * same guarded construction ([createIfAvailable] returns null when Firebase
 * is not configured, so config-less/CI builds simply never prompt).
 */
class FirebaseAppVersionConfigRepository private constructor(
    private val firestore: FirebaseFirestore,
) : AppVersionConfigRepository {

    override suspend fun fetch(): AppVersionConfig? =
        suspendCancellableCoroutine { continuation ->
            firestore
                .collection(CONFIG)
                .document(APP_VERSION)
                .get()
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        // A missing document gives null data → null config →
                        // no prompt, which is exactly the wanted behaviour
                        // before anyone has ever published a version record.
                        continuation.resume(AppVersionConfig.fromStored(task.result?.data))
                    } else {
                        continuation.resumeWithException(
                            task.exception
                                ?: IllegalStateException("App version read failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val CONFIG = "config"
        private const val APP_VERSION = "appVersion"

        fun createIfAvailable(context: Context): AppVersionConfigRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseAppVersionConfigRepository(FirebaseFirestore.getInstance())
        }
    }
}
