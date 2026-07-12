package com.kungsbackacarcommunity.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRecordCoordinatorTest {

    private class FakeRecorder : LoginRecorder {
        var calls = 0
        var failWith: Exception? = null

        override suspend fun recordLogin() {
            calls++
            failWith?.let { throw it }
        }
    }

    @Test
    fun `records the login once`() = runTest {
        val recorder = FakeRecorder()
        LoginRecordCoordinator(recorder).recordLogin()
        assertEquals(1, recorder.calls)
    }

    @Test
    fun `a backend failure is swallowed (best-effort)`() = runTest {
        val recorder = FakeRecorder().apply { failWith = IllegalStateException("permission-denied") }
        // Must not throw.
        LoginRecordCoordinator(recorder).recordLogin()
        assertEquals(1, recorder.calls)
    }

    @Test
    fun `cancellation is rethrown`() = runTest {
        val recorder = FakeRecorder().apply { failWith = CancellationException("c") }
        var rethrown = false
        try {
            LoginRecordCoordinator(recorder).recordLogin()
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }

    @Test
    fun `two concurrent record calls hit the backend exactly once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val recorder =
            object : LoginRecorder {
                var calls = 0

                override suspend fun recordLogin() {
                    calls++
                    gate.await()
                }
            }
        val coordinator = LoginRecordCoordinator(recorder)

        val first = launch { coordinator.recordLogin() }
        val second = launch { coordinator.recordLogin() }
        // The winner holds the lock across gate.await(); the loser tryLock-fails.
        runCurrent()
        assertEquals(1, recorder.calls)

        gate.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, recorder.calls)
    }
}
