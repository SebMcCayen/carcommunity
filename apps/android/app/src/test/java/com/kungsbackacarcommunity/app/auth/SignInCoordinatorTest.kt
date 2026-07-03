package com.kungsbackacarcommunity.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
