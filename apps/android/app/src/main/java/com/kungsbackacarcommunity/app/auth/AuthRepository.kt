package com.kungsbackacarcommunity.app.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Authentication session boundary.
 *
 * Implementations wrap Firebase Auth; the interface stays Firebase-free so
 * sign-in orchestration can be unit-tested with fakes.
 */
interface AuthRepository {
    /** Current session state, updated whenever Firebase auth state changes. */
    val authState: StateFlow<AuthState>

    /**
     * Exchanges a Google ID token for a Firebase session
     * (GoogleAuthProvider credential → signInWithCredential).
     *
     * @throws Exception when Firebase rejects the credential.
     */
    suspend fun signInWithGoogleIdToken(idToken: String)

    /** Clears the Firebase session. Safe to call when already signed out. */
    fun signOut()
}
