package com.kungsbackacarcommunity.app.auth

/** Raised when Google Sign-In cannot run (no OAuth client configured). */
class SignInUnavailableException(message: String) : Exception(message)

/** Raised when the credential flow completes without a usable Google ID token. */
class SignInFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Fetches a Google ID token for the Firebase credential exchange.
 *
 * Kept as an interface so the sign-in flow can be unit-tested without the
 * Credential Manager Play Services dependency.
 */
fun interface GoogleIdTokenProvider {
    /**
     * @throws SignInUnavailableException when sign-in is not configured.
     * @throws SignInFailedException when the flow fails or is dismissed.
     */
    suspend fun fetchGoogleIdToken(): String
}
