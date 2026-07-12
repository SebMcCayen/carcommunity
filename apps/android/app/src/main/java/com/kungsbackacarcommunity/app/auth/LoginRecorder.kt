package com.kungsbackacarcommunity.app.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex

/**
 * Records the current sign-in against the backend so the account's last-activity
 * timestamp (users/{uid}.lastLoginAt) stays fresh — the queryable field the
 * scheduled inactive-account cleanup uses. Firebase-free interface for testability.
 */
interface LoginRecorder {
    /** Records one successful sign-in (auth-recordLogin). Idempotent server-side. */
    suspend fun recordLogin()
}

/**
 * Fire-and-forget coordinator for last-login recording. Invoked once per
 * signed-in uid (a LaunchedEffect in AuthenticatedApp), it calls the backend
 * best-effort: recording last-login is non-critical and must NEVER block or
 * break the signed-in UI, so every non-cancellation failure is swallowed.
 *
 * A member-gated backend rejects non-members with permission-denied; that too is
 * swallowed — such accounts simply have no lastLoginAt and are covered by the
 * sweep's lastLoginAt ?? createdAt fallback.
 *
 * Pure Kotlin so it is unit-testable with a fake recorder.
 */
class LoginRecordCoordinator(
    private val recorder: LoginRecorder,
) {
    // Atomic guard: if a previous record is still in flight, a re-entrant call
    // (e.g. recomposition) is a no-op rather than a duplicate backend call.
    private val actionLock = Mutex()

    suspend fun recordLogin() {
        if (!actionLock.tryLock()) return
        try {
            recorder.recordLogin()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Best-effort: last-login recording must never surface an error.
        } finally {
            actionLock.unlock()
        }
    }
}

/**
 * [LoginRecorder] backed by the auth-recordLogin callable (europe-west1).
 * Guarded ([createIfAvailable]) like the rest of the Firebase wiring — null when
 * google-services.json is absent (CI/local validation builds).
 */
class FirebaseLoginRecorder private constructor(
    private val functions: FirebaseFunctions,
) : LoginRecorder {

    override suspend fun recordLogin() {
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(CALLABLE)
                .call(emptyMap<String, Any>())
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception
                                ?: IllegalStateException("$CALLABLE failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val CALLABLE = "auth-recordLogin"

        fun createIfAvailable(context: Context): LoginRecorder? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseLoginRecorder(FirebaseFunctions.getInstance(REGION))
        }
    }
}
