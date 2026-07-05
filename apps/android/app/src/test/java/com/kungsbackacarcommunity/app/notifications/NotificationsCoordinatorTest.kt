package com.kungsbackacarcommunity.app.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsCoordinatorTest {

    private class FakeRepo : NotificationsRepository {
        val read = mutableListOf<String>()
        var allReadCount = 0
        var failWith: Exception? = null

        override fun observeNotifications(uid: String): Flow<NotificationsState> =
            flowOf(NotificationsState.Loading)

        override suspend fun markRead(notificationId: String) {
            failWith?.let { throw it }
            read += notificationId
        }

        override suspend fun markAllRead() {
            failWith?.let { throw it }
            allReadCount++
        }
    }

    @Test
    fun `markRead and markAllRead call through and end Idle`() = runTest {
        val repo = FakeRepo()
        val coordinator = NotificationsCoordinator(repo)
        coordinator.markRead("n1")
        coordinator.markAllRead()
        assertEquals(listOf("n1"), repo.read)
        assertEquals(1, repo.allReadCount)
        assertEquals(MarkReadStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `a failure surfaces Failed and can reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("x") }
        val coordinator = NotificationsCoordinator(repo)
        coordinator.markRead("n1")
        assertEquals(MarkReadStatus.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(MarkReadStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("c") }
        val coordinator = NotificationsCoordinator(repo)
        var rethrown = false
        try {
            coordinator.markAllRead()
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(MarkReadStatus.Idle, coordinator.status.value)
    }
}
