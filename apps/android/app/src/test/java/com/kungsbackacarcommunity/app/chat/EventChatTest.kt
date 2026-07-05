package com.kungsbackacarcommunity.app.chat

import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.RsvpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventChatTest {

    @Test
    fun `canParticipate requires member, published and going or maybe`() {
        assertTrue(EventChat.canParticipate(true, EventStatus.PUBLISHED, RsvpStatus.GOING))
        assertTrue(EventChat.canParticipate(true, EventStatus.PUBLISHED, RsvpStatus.MAYBE))
        assertFalse(EventChat.canParticipate(true, EventStatus.PUBLISHED, RsvpStatus.NOT_GOING))
        assertFalse(EventChat.canParticipate(true, EventStatus.PUBLISHED, null))
        assertFalse(EventChat.canParticipate(false, EventStatus.PUBLISHED, RsvpStatus.GOING))
        assertFalse(EventChat.canParticipate(true, EventStatus.CANCELLED, RsvpStatus.GOING))
        assertFalse(EventChat.canParticipate(true, null, RsvpStatus.GOING))
    }

    @Test
    fun `isSendable enforces the 1 to MAX trimmed bound`() {
        assertFalse(EventChat.isSendable(""))
        assertFalse(EventChat.isSendable("   "))
        assertTrue(EventChat.isSendable("hi"))
        assertTrue(EventChat.isSendable("x".repeat(EventChat.MESSAGE_MAX_LENGTH)))
        assertFalse(EventChat.isSendable("x".repeat(EventChat.MESSAGE_MAX_LENGTH + 1)))
    }

    @Test
    fun `moderation state parses removed and defaults to visible`() {
        assertEquals(ChatModerationState.REMOVED, ChatModerationState.fromWire("removed"))
        assertEquals(ChatModerationState.VISIBLE, ChatModerationState.fromWire("visible"))
        assertEquals(ChatModerationState.VISIBLE, ChatModerationState.fromWire(null))
        assertEquals(ChatModerationState.VISIBLE, ChatModerationState.fromWire("weird"))
    }

    @Test
    fun `report reasons expose the backend wire values`() {
        assertEquals("harassment", ChatReportReason.HARASSMENT.wire)
        assertEquals("hate_or_abuse", ChatReportReason.HATE_OR_ABUSE.wire)
        assertEquals("unsafe_driving", ChatReportReason.UNSAFE_DRIVING.wire)
        assertEquals("other", ChatReportReason.OTHER.wire)
    }
}
