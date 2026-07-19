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

/** Which stage of the sign-in flow produced a failure — reported for observability. */
enum class SignInStep(val wireName: String) {
    /** Fetching the Google ID token via Credential Manager. */
    CREDENTIAL_FETCH("credential_fetch"),

    /** Exchanging the Google ID token for a Firebase session. */
    FIREBASE_EXCHANGE("firebase_exchange"),
}

/**
 * PII-SAFE, enriched description of a sign-in failure. Everything here is safe to
 * ship off-device: class names and stable library status constants ONLY — never
 * the exception message, credentials, tokens, email, or any PII.
 *
 * - [errorType] — the ROOT-CAUSE exception's simple class name (e.g. the concrete
 *   `NoCredentialException` / `FirebaseAuthInvalidCredentialsException` rather
 *   than the `SignInFailedException` wrapper). This is the primary signal and is
 *   what the backend buckets into a deduplicated public GitHub issue.
 * - [causeChain] — every exception simple class name from the outermost wrapper
 *   down to the root cause, so an admin sees the full unwrap in the private report.
 * - [step] — which stage failed (credential fetch vs Firebase exchange).
 * - [statusCode] — an optional stable status constant carried up from the throw
 *   site (Credential Manager `GetCredentialException.type` or Firebase
 *   `FirebaseAuthException.errorCode`); null when none was exposed.
 */
data class SignInFailureDetails(
    val errorType: String,
    val causeChain: List<String>,
    val step: SignInStep,
    val statusCode: String?,
)

/**
 * Fire-and-forget sink for a sanitized sign-in failure, used for observability
 * during testing. Implementations must never throw and must not block. The
 * [SignInFailureDetails] carry ONLY sanitized class names / stable status
 * constants — NEVER the exception message, credentials, tokens, email, or any PII.
 */
fun interface SignInFailureReporter {
    fun reportSignInFailure(details: SignInFailureDetails)
}

/** No-op reporter (default): keeps [SignInCoordinator] pure and Firebase-free. */
object NoopSignInFailureReporter : SignInFailureReporter {
    override fun reportSignInFailure(details: SignInFailureDetails) = Unit
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

        // Step 1 — fetch the Google ID token. Split from the exchange so a failure
        // can be attributed to the exact stage (SignInStep) for diagnostics.
        val idToken =
            try {
                tokenProvider.fetchGoogleIdToken()
            } catch (cancellation: CancellationException) {
                state.value = SignInStatus.Idle
                throw cancellation
            } catch (cancelled: SignInCancelledException) {
                // The USER dismissed the credential sheet. Not a fault, so it is
                // dropped HERE — before any diagnostics document is written, so
                // there is nothing to store, dedupe, or count. A pre-auth report
                // auto-files a PUBLIC GitHub issue, and filing one for a
                // cancellation "would report the app working correctly" (the same
                // rule the live-share and friends reporters follow; see
                // ClientErrorReporting.kt). This was issue #457.
                //
                // Idle, not Failed: the user chose to back out, so the login screen
                // returns to its resting state instead of accusing them of an error.
                state.value = SignInStatus.Idle
                return
            } catch (unavailable: SignInUnavailableException) {
                // Configuration gap, not a runtime error — never reported as a failure.
                state.value = SignInStatus.Failed(SignInFailure.UNAVAILABLE)
                return
            } catch (failure: Exception) {
                reportFailure(failure, SignInStep.CREDENTIAL_FETCH)
                state.value = SignInStatus.Failed(SignInFailure.GENERIC)
                return
            }

        // Step 2 — exchange the token for a Firebase session.
        try {
            repository.signInWithGoogleIdToken(idToken)
            state.value = SignInStatus.Idle
        } catch (cancellation: CancellationException) {
            state.value = SignInStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            reportFailure(failure, SignInStep.FIREBASE_EXCHANGE)
            state.value = SignInStatus.Failed(SignInFailure.GENERIC)
        }
    }

    /**
     * Builds the sanitized, enriched failure details and hands them to the
     * reporter. Error details (which may reference credentials) are NEVER logged
     * or included — only class names, the failing step, and any stable status
     * constant carried via [SignInDiagnosticInfo]. The reporter is fire-and-forget
     * and must never throw, but guard anyway so a reporting fault can't mask the
     * sign-in failure.
     */
    private fun reportFailure(failure: Throwable, step: SignInStep) {
        try {
            failureReporter.reportSignInFailure(describeFailure(failure, step))
        } catch (_: Exception) {
            // Diagnostics must never crash the app or mask the original error.
        }
    }

    /** Clears a failure state, e.g. when the user dismisses the error. */
    fun resetFailure() {
        if (state.value is SignInStatus.Failed) {
            state.value = SignInStatus.Idle
        }
    }

    companion object {
        /** Bounds the unwrap so a pathological/cyclic cause chain can't run away. */
        private const val MAX_CAUSE_DEPTH = 8

        /**
         * Derives the PII-safe [SignInFailureDetails] from a throwable: the cause
         * chain of simple class names (outermost → root), the root cause as the
         * primary [SignInFailureDetails.errorType], the failing [step], and the
         * first PII-safe [SignInDiagnosticInfo.diagnosticCode] found while
         * unwrapping. Pure — inspects only class names and our own carrier
         * interface, never Android/Firebase types or exception messages.
         */
        internal fun describeFailure(failure: Throwable, step: SignInStep): SignInFailureDetails {
            val chain = causeChainSimpleNames(failure)
            return SignInFailureDetails(
                errorType = chain.lastOrNull() ?: "SignInException",
                causeChain = chain,
                step = step,
                statusCode = firstDiagnosticCode(failure),
            )
        }

        /**
         * Simple class names from [root] down its `cause` chain (outermost first),
         * bounded by [MAX_CAUSE_DEPTH] and cycle-guarded. Blank simple names (e.g.
         * anonymous classes) fall back to `Throwable` so a class-name token is
         * always produced.
         */
        private fun causeChainSimpleNames(root: Throwable): List<String> {
            val names = mutableListOf<String>()
            val seen = mutableSetOf<Throwable>()
            var current: Throwable? = root
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
                names += current.javaClass.simpleName.ifBlank { "Throwable" }
                current = current.cause
                depth++
            }
            return names
        }

        /** First PII-safe diagnostic code carried by any exception in the chain. */
        private fun firstDiagnosticCode(root: Throwable): String? {
            val seen = mutableSetOf<Throwable>()
            var current: Throwable? = root
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
                val code = (current as? SignInDiagnosticInfo)?.diagnosticCode
                if (!code.isNullOrBlank()) return code
                current = current.cause
                depth++
            }
            return null
        }
    }
}
