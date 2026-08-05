package com.kungsbackacarcommunity.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the pure part of the "tap an event notification → open that event"
 * behaviour: every event category carries the event id, non-event categories do
 * not, and a blank id degrades to null (no navigation) rather than opening a
 * blank event.
 */
class EventNotificationsTest {
    private fun item(
        category: NotificationCategory,
        relatedEntityId: String? = null,
    ) = AppNotification(
        id = "n1",
        category = category,
        title = "Nytt event",
        previewText = "\"Bilträff\" har lagts till.",
        body = null,
        isRead = false,
        createdAtMillis = 1_000L,
        actionType = NotificationActionType.NONE,
        relatedEntityId = relatedEntityId,
    )

    @Test
    fun `every event category yields its event id`() {
        for (category in listOf(
            NotificationCategory.EVENT_CREATED,
            NotificationCategory.EVENT_REMINDER,
            NotificationCategory.EVENT_UPDATED,
            NotificationCategory.EVENT_CANCELLED,
        )) {
            assertEquals("e1", EventNotifications.eventId(item(category, "e1")))
        }
    }

    @Test
    fun `a non-event category is never treated as an event`() {
        assertNull(EventNotifications.eventId(item(NotificationCategory.CONVOY_INVITE, "c1")))
        assertNull(EventNotifications.eventId(item(NotificationCategory.FRIEND_REQUEST, "u1")))
        assertNull(EventNotifications.eventId(item(NotificationCategory.SYSTEM_NOTICE, "x")))
    }

    @Test
    fun `a blank or missing id degrades to null so the row navigates nowhere`() {
        assertNull(EventNotifications.eventId(item(NotificationCategory.EVENT_CREATED, null)))
        assertNull(EventNotifications.eventId(item(NotificationCategory.EVENT_CREATED, "")))
        assertNull(EventNotifications.eventId(item(NotificationCategory.EVENT_CREATED, "   ")))
    }
}
