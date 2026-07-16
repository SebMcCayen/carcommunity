package com.kungsbackacarcommunity.app.push

import com.kungsbackacarcommunity.app.notifications.NotificationCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushDisplayTest {

    @Test
    fun `full data message maps every field`() {
        val model =
            PushDisplay.fromMessage(
                data =
                    mapOf(
                        "category" to "event_reminder",
                        "title" to "Meet at the harbor",
                        "previewText" to "Starts in one hour",
                        "body" to "Long body",
                        "actionType" to "open_event",
                        "relatedEntityId" to "event-1",
                        "notificationId" to "n-1",
                    ),
            )
        assertEquals("Meet at the harbor", model.title)
        assertEquals("Starts in one hour", model.body)
        assertEquals(PushChannel.EVENTS.id, model.channelId)
        assertEquals(NotificationCategory.EVENT_REMINDER, model.category)
        assertEquals("open_event", model.actionType)
        assertEquals("event-1", model.relatedEntityId)
        assertEquals("n-1", model.notificationId)
    }

    @Test
    fun `data keys win over the notification block`() {
        val model =
            PushDisplay.fromMessage(
                data = mapOf("title" to "Data title", "previewText" to "Data preview"),
                notificationTitle = "Notification title",
                notificationBody = "Notification body",
            )
        assertEquals("Data title", model.title)
        assertEquals("Data preview", model.body)
    }

    @Test
    fun `notification block fills missing data keys`() {
        val model =
            PushDisplay.fromMessage(
                data = emptyMap(),
                notificationTitle = "Fallback title",
                notificationBody = "Fallback body",
            )
        assertEquals("Fallback title", model.title)
        assertEquals("Fallback body", model.body)
    }

    @Test
    fun `body falls back from previewText to body key`() {
        val model = PushDisplay.fromMessage(data = mapOf("body" to "Only body"))
        assertEquals("Only body", model.body)
    }

    @Test
    fun `missing and unknown keys degrade to a neutral system notice`() {
        val model = PushDisplay.fromMessage(data = mapOf("category" to "not_a_category"))
        assertEquals(NotificationCategory.SYSTEM_NOTICE, model.category)
        assertEquals(PushChannel.GENERAL.id, model.channelId)
        assertNull(model.title)
        assertNull(model.body)
        assertNull(model.notificationId)
        assertNull(model.actionType)
        assertNull(model.relatedEntityId)
    }

    @Test
    fun `blank values count as missing`() {
        val model =
            PushDisplay.fromMessage(
                data = mapOf("title" to "  ", "previewText" to ""),
                notificationTitle = "Real title",
            )
        assertEquals("Real title", model.title)
        assertNull(model.body)
    }

    @Test
    fun `display requires BOTH a signed-in user and the notification permission`() {
        // Signed-out pushes must never display (tokens outlive sign-out;
        // a shared device would leak the previous user's notifications).
        assertFalse(PushDisplay.shouldDisplay(signedIn = false, permissionGranted = true))
        assertFalse(PushDisplay.shouldDisplay(signedIn = false, permissionGranted = false))
        // Signed in but permission denied — dropped.
        assertFalse(PushDisplay.shouldDisplay(signedIn = true, permissionGranted = false))
        // Signed in with permission — shown.
        assertTrue(PushDisplay.shouldDisplay(signedIn = true, permissionGranted = true))
    }

    @Test
    fun `channel selection groups every category`() {
        val expected =
            mapOf(
                NotificationCategory.EVENT_REMINDER to PushChannel.EVENTS,
                NotificationCategory.EVENT_UPDATED to PushChannel.EVENTS,
                NotificationCategory.EVENT_CANCELLED to PushChannel.EVENTS,
                NotificationCategory.ADMIN_MESSAGE to PushChannel.ACCOUNT,
                NotificationCategory.ACCOUNT_WARNING to PushChannel.ACCOUNT,
                NotificationCategory.ACCOUNT_SUSPENSION to PushChannel.ACCOUNT,
                NotificationCategory.SUBSCRIPTION_STATUS to PushChannel.GENERAL,
                NotificationCategory.SYSTEM_NOTICE to PushChannel.GENERAL,
                NotificationCategory.DIRECT_MESSAGE to PushChannel.SOCIAL,
                NotificationCategory.COMMUNITY_CHAT to PushChannel.SOCIAL,
                NotificationCategory.CONVOY_CHAT to PushChannel.SOCIAL,
                NotificationCategory.FRIEND_REQUEST to PushChannel.SOCIAL,
                NotificationCategory.CONVOY_INVITE to PushChannel.SOCIAL,
            )
        // Exhaustive: a new category must be assigned a channel here too.
        assertEquals(NotificationCategory.entries.toSet(), expected.keys)
        expected.forEach { (category, channel) ->
            assertEquals(channel, PushDisplay.channelFor(category))
        }
    }
}
