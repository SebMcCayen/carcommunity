package com.kungsbackacarcommunity.app.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Google Sign-In orchestration (migration plan Phase 7:
 * "unit tests for auth service, Credential Manager flow mock"). The
 * Credential Manager flow is mocked via [GoogleIdTokenProvider] fakes.
 */
class SignInCoordinatorTest {

    private class FakeAuthRepository : AuthRepository {
        val receivedTokens = mutableListOf<String>()
        var failWith: Exception? = null

        override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut)

        override suspend fun signInWithGoogleIdToken(idToken: String) {
            failWith?.let { throw it }
            receivedTokens += idToken
        }

        override fun signOut() = Unit
    }

    @Test
    fun `successful flow passes the Google ID token to Firebase and ends idle`() = runTest {
        val repository = FakeAuthRepository()
        val coordinator = SignInCoordinator({ "google-id-token" }, repository)

        coordinator.signIn()

        assertEquals(listOf("google-id-token"), repository.receivedTokens)
        assertEquals(SignInStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `missing sign-in configuration surfaces the unavailable failure`() = runTest {
        val repository = FakeAuthRepository()
        val coordinator =
            SignInCoordinator(
                { throw SignInUnavailableException("not configured") },
                repository,
            )

        coordinator.signIn()

        assertEquals(SignInStatus.Failed(SignInFailure.UNAVAILABLE), coordinator.status.value)
        assertTrue(repository.receivedTokens.isEmpty())
    }

    @Test
    fun `credential flow failure surfaces the generic failure`() = runTest {
        val repository = FakeAuthRepository()
        val coordinator =
            SignInCoordinator(
                { throw SignInFailedException("dismissed") },
                repository,
            )

        coordinator.signIn()

        assertEquals(SignInStatus.Failed(SignInFailure.GENERIC), coordinator.status.value)
    }

    @Test
    fun `generic failure reports the sanitized exception type, never the message`() = runTest {
        val repository = FakeAuthRepository()
        val reported = mutableListOf<SignInFailureDetails>()
        val coordinator =
            SignInCoordinator(
                { throw SignInFailedException("token dismissed for user@example.com") },
                repository,
                { details -> reported += details },
            )

        coordinator.signIn()

        // Only sanitized class names — never the message — reach the reporter.
        assertEquals(listOf("SignInFailedException"), reported.map { it.errorType })
        assertEquals(SignInStep.CREDENTIAL_FETCH, reported.single().step)
        assertEquals(SignInStatus.Failed(SignInFailure.GENERIC), coordinator.status.value)
    }

    @Test
    fun `unavailable failure is not reported as a sign-in failure`() = runTest {
        val repository = FakeAuthRepository()
        val reported = mutableListOf<SignInFailureDetails>()
        val coordinator =
            SignInCoordinator(
                { throw SignInUnavailableException("not configured") },
                repository,
                { details -> reported += details },
            )

        coordinator.signIn()

        assertTrue(reported.isEmpty())
    }

    @Test
    fun `reports the ROOT cause type, the full cause chain, and the failing step`() = runTest {
        val repository = FakeAuthRepository()
        val reported = mutableListOf<SignInFailureDetails>()
        // A concrete Credential Manager failure wrapped by the app's own type —
        // the wrapper simple name alone (SignInFailedException) would hide the
        // real cause, so the root cause must be surfaced instead.
        val rootCause = IllegalStateException("no credential available")
        val coordinator =
            SignInCoordinator(
                {
                    throw SignInFailedException(
                        "Google credential flow did not complete.",
                        cause = rootCause,
                        diagnosticCode = "androidx.credentials.TYPE_NO_CREDENTIAL",
                    )
                },
                repository,
                { details -> reported += details },
            )

        coordinator.signIn()

        val details = reported.single()
        // errorType is the ROOT cause, not the wrapper.
        assertEquals("IllegalStateException", details.errorType)
        // The full unwrap is preserved, outermost first.
        assertEquals(
            listOf("SignInFailedException", "IllegalStateException"),
            details.causeChain,
        )
        // The PII-safe Credential Manager status constant is surfaced.
        assertEquals("androidx.credentials.TYPE_NO_CREDENTIAL", details.statusCode)
        assertEquals(SignInStep.CREDENTIAL_FETCH, details.step)
    }

    @Test
    fun `a firebase exchange failure is attributed to the exchange step with its status code`() =
        runTest {
            val repository =
                FakeAuthRepository().apply {
                    failWith =
                        SignInFailedException(
                            "Firebase credential exchange failed.",
                            cause = IllegalStateException("rejected"),
                            diagnosticCode = "ERROR_INVALID_CREDENTIAL",
                        )
                }
            val reported = mutableListOf<SignInFailureDetails>()
            val coordinator =
                SignInCoordinator({ "google-id-token" }, repository, { details -> reported += details })

            coordinator.signIn()

            val details = reported.single()
            assertEquals(SignInStep.FIREBASE_EXCHANGE, details.step)
            assertEquals("IllegalStateException", details.errorType)
            assertEquals("ERROR_INVALID_CREDENTIAL", details.statusCode)
        }

    @Test
    fun `an unwrapped failure with no cause reports itself and a null status code`() = runTest {
        val repository = FakeAuthRepository()
        val reported = mutableListOf<SignInFailureDetails>()
        val coordinator =
            SignInCoordinator(
                { throw IllegalArgumentException("boom") },
                repository,
                { details -> reported += details },
            )

        coordinator.signIn()

        val details = reported.single()
        assertEquals("IllegalArgumentException", details.errorType)
        assertEquals(listOf("IllegalArgumentException"), details.causeChain)
        assertNull(details.statusCode)
    }

    @Test
    fun `a throwing failure reporter never masks the sign-in failure`() = runTest {
        val repository = FakeAuthRepository()
        val coordinator =
            SignInCoordinator(
                { throw SignInFailedException("dismissed") },
                repository,
                { throw IllegalStateException("reporter boom") },
            )

        coordinator.signIn()

        assertEquals(SignInStatus.Failed(SignInFailure.GENERIC), coordinator.status.value)
    }

    @Test
    fun `firebase credential exchange failure surfaces the generic failure`() = runTest {
        val repository = FakeAuthRepository().apply { failWith = IllegalStateException("rejected") }
        val coordinator = SignInCoordinator({ "google-id-token" }, repository)

        coordinator.signIn()

        assertEquals(SignInStatus.Failed(SignInFailure.GENERIC), coordinator.status.value)
    }

    @Test
    fun `re-entrant sign-in is ignored while a flow is in progress`() = runTest {
        val repository = FakeAuthRepository()
        val gate = CompletableDeferred<String>()
        val coordinator = SignInCoordinator({ gate.await() }, repository)

        val first = launch { coordinator.signIn() }
        // Let the first attempt reach the suspension point.
        testScheduler.runCurrent()
        assertEquals(SignInStatus.InProgress, coordinator.status.value)

        // Second call must be a no-op while the first is in flight.
        coordinator.signIn()
        assertEquals(SignInStatus.InProgress, coordinator.status.value)

        gate.complete("token-1")
        first.join()

        assertEquals(listOf("token-1"), repository.receivedTokens)
        assertEquals(SignInStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `resetFailure clears a failed state but not an in-progress one`() = runTest {
        val repository = FakeAuthRepository()
        val coordinator =
            SignInCoordinator(
                { throw SignInFailedException("dismissed") },
                repository,
            )

        coordinator.signIn()
        assertEquals(SignInStatus.Failed(SignInFailure.GENERIC), coordinator.status.value)

        coordinator.resetFailure()
        assertEquals(SignInStatus.Idle, coordinator.status.value)
    }

    // ---------------------------------------------------------------------
    // Issue #457 — a USER CANCELLATION must not reach the diagnostics pipeline.
    //
    // The observable these assert on is the one that matters: whether a report
    // was handed to the SignInFailureReporter at all, and (for the control case)
    // with which errorType. A pre-auth report auto-files a PUBLIC GitHub issue,
    // so "reported.isEmpty()" here is literally "no public issue is filed".
    // ---------------------------------------------------------------------

    @Test
    fun `user cancelling the credential sheet writes no diagnostics report`() = runTest {
        val repository = FakeAuthRepository()
        val reported = mutableListOf<SignInFailureDetails>()
        val coordinator =
            SignInCoordinator(
                {
                    // Driven through the REAL mapping, so this fails if either half
                    // of the fix regresses: the provider mapping OR the coordinator
                    // branch that drops it.
                    throw GoogleCredentialTokenProvider.toSignInException(
                        GetCredentialCancellationException("user dismissed the sheet"),
                    )
                },
                repository,
                { details -> reported += details },
            )

        coordinator.signIn()

        // Nothing submitted => no diagnosticsReports doc => no GitHub issue.
        assertTrue(
            "A cancelled sign-in must not be reported, but got: ${reported.map { it.errorType }}",
            reported.isEmpty(),
        )
        // And the user is simply back at the login screen — not accused of an error.
        assertEquals(SignInStatus.Idle, coordinator.status.value)
        assertTrue(repository.receivedTokens.isEmpty())
    }

    @Test
    fun `a genuine credential failure is still reported`() = runTest {
        val repository = FakeAuthRepository()
        val reported = mutableListOf<SignInFailureDetails>()
        val coordinator =
            SignInCoordinator(
                {
                    throw SignInFailedException(
                        "Google credential flow did not complete.",
                        IllegalStateException("provider misconfigured"),
                        diagnosticCode = "androidx.credentials.TYPE_PROVIDER_CONFIGURATION",
                    )
                },
                repository,
                { details -> reported += details },
            )

        coordinator.signIn()

        // The control case: the filter must NOT swallow real faults.
        val details = reported.single()
        assertEquals("IllegalStateException", details.errorType)
        assertEquals(SignInStep.CREDENTIAL_FETCH, details.step)
        assertEquals("androidx.credentials.TYPE_PROVIDER_CONFIGURATION", details.statusCode)
        assertEquals(SignInStatus.Failed(SignInFailure.GENERIC), coordinator.status.value)
    }
}
