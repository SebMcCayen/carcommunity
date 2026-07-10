package com.kungsbackacarcommunity.app.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [AuthRepository] backed by Firebase Auth.
 *
 * The Firebase SDK owns token persistence and refresh internally — tokens are
 * never manually stored or logged (docs/auth-mobile-requirements.md).
 *
 * Construction is guarded: [createIfAvailable] returns null when Firebase is
 * not configured in this build (no google-services.json), so the app renders
 * an unauthenticated shell instead of crashing.
 *
 * A single instance is cached in [createIfAvailable]: the auth-state listener
 * registered in `init` lives for the process lifetime by design, and caching
 * prevents listener accumulation across Activity recreations.
 */
class FirebaseAuthRepository private constructor(
    private val firebaseAuth: FirebaseAuth,
) : AuthRepository {

    private val state = MutableStateFlow(firebaseAuth.currentUser.toAuthState())

    override val authState: StateFlow<AuthState> = state.asStateFlow()

    init {
        firebaseAuth.addAuthStateListener { auth ->
            state.value = auth.currentUser.toAuthState()
        }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        suspendCancellableCoroutine { continuation ->
            firebaseAuth.signInWithCredential(credential).addOnCompleteListener { task ->
                // The task may complete after the caller was cancelled (e.g. the
                // user left the screen) — resuming a cancelled continuation throws,
                // so guard with isActive.
                if (!continuation.isActive) return@addOnCompleteListener
                if (task.isSuccessful) {
                    continuation.resume(Unit)
                } else {
                    val cause =
                        task.exception ?: IllegalStateException("Sign-in failed without a cause")
                    // Preserve the concrete Firebase exception as `cause` (so the
                    // coordinator's cause chain names it) and lift its stable,
                    // PII-safe errorCode (e.g. ERROR_INVALID_CREDENTIAL) into the
                    // diagnostic code, letting the pure coordinator report the real
                    // Firebase status without importing Firebase types. Never logs
                    // the message.
                    continuation.resumeWithException(
                        SignInFailedException(
                            "Firebase credential exchange failed.",
                            cause,
                            diagnosticCode = (cause as? FirebaseAuthException)?.errorCode,
                        ),
                    )
                }
            }
        }
    }

    override fun signOut() {
        firebaseAuth.signOut()
    }

    companion object {
        @Volatile
        private var cached: FirebaseAuthRepository? = null

        /**
         * Returns the process-wide repository when Firebase is configured for
         * this build, or null when google-services.json is absent (CI, local
         * validation builds — see apps/android/README.md).
         */
        fun createIfAvailable(context: Context): AuthRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return cached ?: synchronized(this) {
                cached ?: FirebaseAuthRepository(FirebaseAuth.getInstance()).also { cached = it }
            }
        }
    }
}

private fun FirebaseUser?.toAuthState(): AuthState =
    if (this == null) AuthState.SignedOut else AuthState.SignedIn(uid, displayName)
