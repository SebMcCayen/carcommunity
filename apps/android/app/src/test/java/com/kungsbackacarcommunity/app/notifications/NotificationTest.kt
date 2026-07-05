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
