package com.kungsbackacarcommunity.app.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
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
                if (task.isSuccessful) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        task.exception ?: IllegalStateException("Sign-in failed without a cause"),
                    )
                }
            }
        }
    }

    override fun signOut() {
        firebaseAuth.signOut()
    }

    companion object {
        /**
         * Returns a repository when Firebase is configured for this build,
         * or null when google-services.json is absent (CI, local validation
         * builds — see apps/android/README.md).
         */
        fun createIfAvailable(context: Context): AuthRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseAuthRepository(FirebaseAuth.getInstance())
        }
    }
}

private fun FirebaseUser?.toAuthState(): AuthState =
    if (this == null) AuthState.SignedOut else AuthState.SignedIn(uid, displayName)
