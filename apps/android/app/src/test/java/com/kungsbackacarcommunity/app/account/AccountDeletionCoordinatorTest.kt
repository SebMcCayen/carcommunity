package com.kungsbackacarcommunity.app.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionCoordinatorTest {

    private class FakeRepo : AccountDeletionRepository {
        val reasons = mutableListOf<String?>()
        var failWith: Exception? = null

        override suspend fun deleteAccount(reason: String?) {
            failWith?.let { throw it }
            reasons += reason
        }
    }

    @Test
    fun `delete succeeds to Done`() = runTest {
        val repo = FakeRepo()
        val coordinator = AccountDeletionCoordinator(repo)
        coordinator.delete(null)
        assertEquals(listOf<String?>(null), repo.reasons)
        assertEquals(AccountDeletionStatus.Done, coordinator.status.value)
    }

    @Test
    fun `a failed delete surfaces Failed and can reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("nope") }
        val coordinator = AccountDeletionCoordinator(repo)
        coordinator.delete(null)
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(AccountDeletionStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("c") }
        val coordinator = AccountDeletionCoordinator(repo)
        var rethrown = false
        try {
            coordinator.delete(null)
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(AccountDeletionStatus.Idle, coordinator.status.value)
    }
}
