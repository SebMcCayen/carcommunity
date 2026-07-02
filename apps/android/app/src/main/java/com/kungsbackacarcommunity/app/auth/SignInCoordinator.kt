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
 * Orchestrates Google Sign-In: fetch a Google ID token, exchange it for a
 * Firebase session. Pure Kotlin (no Firebase/Android types) so the flow is
 * unit-testable with fakes.
 */
class SignInCoordinator(
    private val tokenProvider: GoogleIdTokenProvider,
    private val repository: AuthRepository,
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
            state.value = SignInStatus.Failed(SignInFailure.GENERIC)
        }
    }

    /** Clears a failure state, e.g. when the user dismisses the error. */
    fun resetFailure() {
        if (state.value is SignInStatus.Failed) {
            state.value = SignInStatus.Idle
        }
    }
}
