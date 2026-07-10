package com.kungsbackacarcommunity.app.notifications

import org.junit.Assert.assertEquals
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
