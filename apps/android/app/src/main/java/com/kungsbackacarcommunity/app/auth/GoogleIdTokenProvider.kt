package com.kungsbackacarcommunity.app.auth

/**
 * Carries a PII-SAFE diagnostic code from the throw site (where the concrete
 * Credential Manager / Firebase exception is known) up to the pure
 * [SignInCoordinator], which must stay free of Android/Firebase types. The
 * code is a stable library constant — e.g. Credential Manager's
 * `GetCredentialException.type` (`androidx.credentials.TYPE_NO_CREDENTIAL`) or
 * Firebase's `FirebaseAuthException.errorCode` (`ERROR_INVALID_CREDENTIAL`) —
 * NEVER an exception message, token, credential, email, or any PII.
 */
interface SignInDiagnosticInfo {
    val diagnosticCode: String?
}

/** Raised when Google Sign-In cannot run (no OAuth client configured). */
class SignInUnavailableException(message: String) : Exception(message)

/**
 * Raised when the credential flow or the Firebase exchange fails. Wraps the
 * concrete cause and, when available, carries its PII-safe [diagnosticCode]
 * (see [SignInDiagnosticInfo]) so the diagnostics pipeline can report the real
 * status without the coordinator touching Android/Firebase types.
 */
class SignInFailedException(
    message: String,
    cause: Throwable? = null,
    override val diagnosticCode: String? = null,
) : Exception(message, cause), SignInDiagnosticInfo

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
