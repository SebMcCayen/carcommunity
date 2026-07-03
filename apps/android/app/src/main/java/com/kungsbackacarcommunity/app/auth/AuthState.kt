package com.kungsbackacarcommunity.app.auth

/**
 * Authenticated-session state as observed from Firebase Auth.
 *
 * The backend is always the source of truth for roles, entitlements, and
 * moderation status — this state only reflects whether a Firebase session
 * exists on the device (docs/auth-mobile-requirements.md).
 */
sealed interface AuthState {
    /**
     * Firebase is not configured in this build (no google-services.json).
     * Sign-in is unavailable; the app must not crash.
     */
    data object Unavailable : AuthState

    /** No Firebase session on the device. */
    data object SignedOut : AuthState

    /** A Firebase session exists. [uid] is the canonical identity key. */
    data class SignedIn(
        val uid: String,
        val displayName: String?,
    ) : AuthState
}
