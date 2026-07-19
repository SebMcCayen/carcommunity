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
 * Raised when the USER dismissed the Google credential sheet (swiped it away or
 * pressed back). This is a deliberate user choice, NOT a fault: the app did
 * exactly what it should.
 *
 * It exists as its own type purely so [SignInCoordinator] can drop it before the
 * diagnostics pipeline sees it. A pre-auth sign-in report auto-files a PUBLIC
 * GitHub issue (functions/src/diagnostics/signInIssues-core.ts), so reporting a
 * cancellation would file an issue for the app WORKING CORRECTLY — the same line
 * the client error reporter already draws for expected business outcomes ("not a
 * member", "recipient blocked"; see ClientErrorReporting.kt, "Only report genuine
 * FAULTS"). Issue #457 was exactly this: one user tapping back.
 *
 * Deliberately NOT a [SignInFailedException] subclass — the coordinator selects on
 * type, and a subclass would be caught by the reporting branch.
 */
class SignInCancelledException(message: String) : Exception(message)

/**
 * Raised when there is NO Google account on the device at all, so Credential
 * Manager has nothing to offer (`NoCredentialException`).
 *
 * This is an ACTIONABLE user-side state, not an app fault: the device is simply
 * missing a prerequisite, and the user can fix it in about two taps by adding a
 * Google account. The sign-in screen therefore shows specific guidance (and,
 * where the system exposes it, a button straight to the add-account screen)
 * instead of the generic "sign-in failed, try again", which was a dead end — the
 * user could re-tap forever and nothing would change.
 *
 * Like [SignInCancelledException] it is dropped by [SignInCoordinator] before
 * the diagnostics pipeline sees it. That is a CHANGE of the call made in the fix
 * for issue #457, and deliberately so: at that point this was a dead end, and the
 * reasoning was "actionable -> drop the report, dead end -> keep the visibility".
 * Now that the user has a clear path forward it is an expected state, and a
 * pre-auth report auto-files a PUBLIC GitHub issue
 * (functions/src/diagnostics/signInIssues-core.ts) — filing one every time
 * someone opens the app on a fresh device would report the app working
 * correctly, the same line the live-share and friends reporters already draw
 * (see ClientErrorReporting.kt, "Only report genuine FAULTS").
 *
 * Deliberately NOT a [SignInFailedException] subclass — the coordinator selects
 * on type, and a subclass would be caught by the reporting branch.
 */
class SignInNoGoogleAccountException(message: String) : Exception(message)

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
     * @throws SignInCancelledException when the user dismissed the credential sheet.
     * @throws SignInNoGoogleAccountException when the device has no Google account.
     * @throws SignInFailedException when the flow genuinely fails.
     */
    suspend fun fetchGoogleIdToken(): String
}
