package com.kungsbackacarcommunity.app.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsCoordinatorTest {

    private class FakeRepo : NotificationsRepository {
        val read = mutableListOf<String>()
        var allReadCount = 0
        var failWith: Exception? = null
        val deleted = mutableListOf<String>()
        var deleteAllCount = 0

        /** Set to fail ONLY the delete calls, leaving mark-read working. */
        var failDeletesWith: Exception? = null

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

        override suspend fun deleteNotification(notificationId: String) {
            failDeletesWith?.let { throw it }
            deleted += notificationId
        }

        override suspend fun deleteAll() {
            failDeletesWith?.let { throw it }
            deleteAllCount++
        }
    }

    private fun notification(id: String) =
        AppNotification(
            id = id,
            category = NotificationCategory.SYSTEM_NOTICE,
            title = "Titel $id",
            previewText = null,
            body = null,
            isRead = true,
            createdAtMillis = 0L,
        )

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

    // ── Optimistic delete ──────────────────────────────────────────────────

    @Test
    fun `a successful delete hides the row and reports no error`() = runTest {
        val repo = FakeRepo()
        val coordinator = NotificationsCoordinator(repo)

        coordinator.delete("n1")

        assertEquals(listOf("n1"), repo.deleted)
        assertEquals(setOf("n1"), coordinator.pendingDeletes.value)
        assertNull(coordinator.deleteError.value)
        // The row is gone from what the screen renders, while the snapshot
        // itself is untouched.
        val items = listOf(notification("n1"), notification("n2"))
        assertEquals(
            listOf("n2"),
            Notifications.visibleItems(items, coordinator.pendingDeletes.value).map { it.id },
        )
    }

    @Test
    fun `a failed delete puts the row back instead of silently dropping it`() = runTest {
        // The whole point of the optimistic path: a notification that still
        // exists on the server must never disappear from the inbox because a
        // delete failed.
        val repo = FakeRepo().apply { failDeletesWith = IllegalStateException("offline") }
        val coordinator = NotificationsCoordinator(repo)
        val items = listOf(notification("n1"), notification("n2"))

        coordinator.delete("n1")

        assertEquals(emptySet<String>(), coordinator.pendingDeletes.value)
        assertEquals(
            listOf("n1", "n2"),
            Notifications.visibleItems(items, coordinator.pendingDeletes.value).map { it.id },
        )
        assertEquals(NotificationDeleteError.SINGLE, coordinator.deleteError.value)

        coordinator.clearDeleteError()
        assertNull(coordinator.deleteError.value)
    }

    @Test
    fun `a repeated swipe on the same row does not call twice`() = runTest {
        // A second in-flight call for the same id would, on failure, restore a
        // row the first call had already removed.
        val repo = FakeRepo()
        val coordinator = NotificationsCoordinator(repo)

        coordinator.delete("n1")
        coordinator.delete("n1")

        assertEquals(listOf("n1"), repo.deleted)
    }

    @Test
    fun `delete-all hides every visible row and empties on success`() = runTest {
        val repo = FakeRepo()
        val coordinator = NotificationsCoordinator(repo)
        val items = listOf(notification("n1"), notification("n2"))

        coordinator.deleteAll(items.map { it.id })

        assertEquals(1, repo.deleteAllCount)
        assertEquals(setOf("n1", "n2"), coordinator.pendingDeletes.value)
        assertTrue(Notifications.visibleItems(items, coordinator.pendingDeletes.value).isEmpty())
    }

    @Test
    fun `a failed delete-all restores every row it hid`() = runTest {
        val repo = FakeRepo().apply { failDeletesWith = IllegalStateException("offline") }
        val coordinator = NotificationsCoordinator(repo)
        val items = listOf(notification("n1"), notification("n2"))

        coordinator.deleteAll(items.map { it.id })

        assertEquals(emptySet<String>(), coordinator.pendingDeletes.value)
        assertEquals(
            listOf("n1", "n2"),
            Notifications.visibleItems(items, coordinator.pendingDeletes.value).map { it.id },
        )
        assertEquals(NotificationDeleteError.ALL, coordinator.deleteError.value)
    }

    @Test
    fun `a failed delete-all keeps a single delete that was already hidden`() = runTest {
        // n1's own delete succeeded and is still waiting for the snapshot to
        // catch up. A delete-all that fails must restore only what IT hid,
        // otherwise n1 flickers back into a list it has already left.
        val repo = FakeRepo()
        val coordinator = NotificationsCoordinator(repo)
        coordinator.delete("n1")
        repo.failDeletesWith = IllegalStateException("offline")

        coordinator.deleteAll(listOf("n1", "n2"))

        assertEquals(setOf("n1"), coordinator.pendingDeletes.value)
    }

    @Test
    fun `a landed delete is retired once the snapshot drops the item`() = runTest {
        // Otherwise the hidden set grows for the life of the screen.
        val repo = FakeRepo()
        val coordinator = NotificationsCoordinator(repo)
        coordinator.delete("n1")

        // The listener has not caught up yet: n1 is still in the snapshot, so
        // it must stay hidden.
        coordinator.onSnapshot(listOf(notification("n1"), notification("n2")))
        assertEquals(setOf("n1"), coordinator.pendingDeletes.value)

        // Now the delete is visible in the snapshot and the id has no work left.
        coordinator.onSnapshot(listOf(notification("n2")))
        assertEquals(emptySet<String>(), coordinator.pendingDeletes.value)
    }

    @Test
    fun `delete cancellation is rethrown and restores the row`() = runTest {
        val repo = FakeRepo().apply { failDeletesWith = CancellationException("c") }
        val coordinator = NotificationsCoordinator(repo)
        var rethrown = false
        try {
            coordinator.delete("n1")
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(emptySet<String>(), coordinator.pendingDeletes.value)
        // A cancellation is not a refusal, so it is not reported as one.
        assertNull(coordinator.deleteError.value)
    }
}
