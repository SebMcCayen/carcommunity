package com.kungsbackacarcommunity.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing progress of an explicit sign-in attempt. */
sealed interface SignInStatus {
    data object Idle : SignInStatus

    data object InProgress : SignInStatus

    data class Failed(val reason: SignInFailure) : SignInStatus
}

enum class SignInFailure {
    /** Sign-in cannot run in this build (no OAuth client configured). */
    UNAVAILABLE,

    /** The credential flow or Firebase exchange failed. */
    GENERIC,
}

/**
 * Fire-and-forget sink for a sanitized sign-in failure, used for observability
 * during testing. Implementations must never throw and must not block. The
 * [errorType] is the failing exception's simple class name (e.g. a Credential
 * Manager / Firebase auth exception type) — NEVER the exception message,
 * credentials, tokens, email, or any PII.
 */
fun interface SignInFailureReporter {
    fun reportSignInFailure(errorType: String)
}

/** No-op reporter (default): keeps [SignInCoordinator] pure and Firebase-free. */
object NoopSignInFailureReporter : SignInFailureReporter {
    override fun reportSignInFailure(errorType: String) = Unit
}

/**
 * Orchestrates Google Sign-In: fetch a Google ID token, exchange it for a
 * Firebase session. Pure Kotlin (no Firebase/Android types) so the flow is
 * unit-testable with fakes.
 */
class SignInCoordinator(
    private val tokenProvider: GoogleIdTokenProvider,
    private val repository: AuthRepository,
    private val failureReporter: SignInFailureReporter = NoopSignInFailureReporter,
) {
    private val state = MutableStateFlow<SignInStatus>(SignInStatus.Idle)

    val status: StateFlow<SignInStatus> = state.asStateFlow()

    /** Runs one sign-in attempt. Re-entrant calls while in progress are ignored. */
    suspend fun signIn() {
        if (state.value == SignInStatus.InProgress) return
        state.value = SignInStatus.InProgress
        try {
            val idToken = tokenProvider.fetchGoogleIdToken()
            repository.signInWithGoogleIdToken(idToken)
            state.value = SignInStatus.Idle
        } catch (cancellation: CancellationException) {
            state.value = SignInStatus.Idle
            throw cancellation
        } catch (unavailable: SignInUnavailableException) {
            state.value = SignInStatus.Failed(SignInFailure.UNAVAILABLE)
        } catch (failure: Exception) {
            // Error details (which may reference credentials) are never logged.
            // Only the exception's simple class name — a sanitized error type,
            // never the message — is reported for observability. The reporter is
            // fire-and-forget and must never throw, but guard anyway so a
            // reporting fault can't mask the sign-in failure.
            state.value = SignInStatus.Failed(SignInFailure.GENERIC)
            try {
                failureReporter.reportSignInFailure(
                    failure.javaClass.simpleName.ifBlank { "SignInException" },
                )
            } catch (_: Exception) {
                // Diagnostics must never crash the app or mask the original error.
            }
        }
    }

    /** Clears a failure state, e.g. when the user dismisses the error. */
    fun resetFailure() {
        if (state.value is SignInStatus.Failed) {
            state.value = SignInStatus.Idle
        }
    }
}
