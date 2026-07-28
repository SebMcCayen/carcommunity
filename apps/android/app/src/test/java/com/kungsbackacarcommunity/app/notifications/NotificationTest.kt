package com.kungsbackacarcommunity.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NotificationTest {

    @Test
    fun `category parses wire values and defaults to system notice`() {
        assertEquals(NotificationCategory.EVENT_REMINDER, NotificationCategory.fromWire("event_reminder"))
        assertEquals(NotificationCategory.ACCOUNT_SUSPENSION, NotificationCategory.fromWire("account_suspension"))
        assertEquals(NotificationCategory.SYSTEM_NOTICE, NotificationCategory.fromWire("mystery"))
        assertEquals(NotificationCategory.SYSTEM_NOTICE, NotificationCategory.fromWire(null))
    }

    @Test
    fun `unreadCount counts only unread`() {
        val items = listOf(item("a", read = false), item("b", read = true), item("c", read = false))
        assertEquals(2, Notifications.unreadCount(items))
        assertEquals(0, Notifications.unreadCount(emptyList()))
    }

    @Test
    fun `inbox query limit is fifty`() {
        assertEquals(50L, Notifications.INBOX_QUERY_LIMIT)
    }

    @Test
    fun `sortedForInbox matches the query ordering so the cap keeps the newest items`() {
        // The Firestore listener orders by createdAt descending before applying
        // INBOX_QUERY_LIMIT; the client-side sort must agree so the displayed
        // order is exactly the capped query order.
        val items = (1..60).map { item("n$it", createdAt = it.toLong()) }.shuffled()
        val capped = Notifications.sortedForInbox(items).take(Notifications.INBOX_QUERY_LIMIT.toInt())
        assertEquals(50, capped.size)
        assertEquals("n60", capped.first().id)
        assertEquals("n11", capped.last().id)
    }

    @Test
    fun `sortedForInbox is newest first with untimed last`() {
        val items =
            listOf(
                item("old", createdAt = 100L),
                item("none", createdAt = null),
                item("new", createdAt = 300L),
                item("mid", createdAt = 200L),
            )
        assertEquals(listOf("new", "mid", "old", "none"), Notifications.sortedForInbox(items).map { it.id })
    }

    // ── Optimistic-delete view ─────────────────────────────────────────────

    @Test
    fun `visibleItems hides pending deletes and keeps the order`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(
            listOf("a", "c"),
            Notifications.visibleItems(items, setOf("b")).map { it.id },
        )
        // Nothing pending returns the very same list rather than a copy — this
        // is the common case, hit on every recomposition.
        assertSame(items, Notifications.visibleItems(items, emptySet()))
    }

    @Test
    fun `visibleItems ignores ids that are not in the list`() {
        val items = listOf(item("a"))
        assertEquals(listOf("a"), Notifications.visibleItems(items, setOf("ghost")).map { it.id })
    }

    @Test
    fun `visibleItems can empty the list, which is what shows the empty state`() {
        val items = listOf(item("a"), item("b"))
        assertEquals(
            emptyList<String>(),
            Notifications.visibleItems(items, setOf("a", "b")).map { it.id },
        )
    }

    @Test
    fun `prunePendingDeletes retires ids the server no longer returns`() {
        // "landed" was deleted successfully and has left the snapshot;
        // "inFlight" is still on screen while its call is running.
        val items = listOf(item("inFlight"), item("other"))
        assertEquals(
            setOf("inFlight"),
            Notifications.prunePendingDeletes(setOf("landed", "inFlight"), items),
        )
    }

    @Test
    fun `prunePendingDeletes empties once every delete has landed`() {
        assertEquals(
            emptySet<String>(),
            Notifications.prunePendingDeletes(setOf("a", "b"), emptyList()),
        )
    }

    private fun item(id: String, read: Boolean = false, createdAt: Long? = 0L) =
        AppNotification(
            id = id,
            category = NotificationCategory.SYSTEM_NOTICE,
            title = "Title $id",
            previewText = "preview",
            body = null,
            isRead = read,
            createdAtMillis = createdAt,
        )
}
