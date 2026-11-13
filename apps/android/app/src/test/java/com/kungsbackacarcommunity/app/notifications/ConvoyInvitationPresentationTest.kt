package com.kungsbackacarcommunity.app.notifications

import org.junit.Assert.*
import org.junit.Test

class ConvoyInvitationPresentationTest {
    private fun invite(id: String, created: Long = 1_000) = AppNotification(
        id, NotificationCategory.CONVOY_INVITE, "Konvoj", "Linnea bjuder in", null,
        false, created, relatedEntityId = "convoy-$id", inviterDisplayName = "Linnea Andersson",
    )

    @Test fun `expires at thirty seconds without reading or declining the invitation`() {
        val item = invite("one")
        val shown = ConvoyInvitationPresentation(1_000).update(listOf(item), 200)
        assertEquals("one", shown.currentId)
        assertEquals(30_200, shown.deadlineMillis)
        assertEquals("one", shown.update(listOf(item), 30_199).currentId)
        val expired = shown.update(listOf(item), 30_200)
        assertNull(expired.currentId)
        assertFalse(item.isRead)
        assertNull(expired.update(listOf(item), 31_000).currentId)
    }

    @Test fun `repeat snapshots and restored presentation retain deadline`() {
        val item = invite("one")
        val shown = ConvoyInvitationPresentation(1_000).update(listOf(item), 200)
        val restored = shown.copy()
        assertEquals(30_200, restored.update(listOf(item), 20_000).deadlineMillis)
    }

    @Test fun `old initial inbox is ignored but arrival during initial loading is displayed`() {
        val old = invite("old", 999)
        val fresh = invite("fresh", 1_001)
        val shown = ConvoyInvitationPresentation(1_000).update(listOf(old, fresh), 200)
        assertEquals("fresh", shown.currentId)
        assertTrue(shown.seenIds.contains("old"))
    }

    @Test fun `queued invitation gets its own thirty seconds and stale completion cannot dismiss it`() {
        val first = invite("first")
        val second = invite("second", 1_001)
        val shown = ConvoyInvitationPresentation(1_000).update(listOf(first), 200)
            .update(listOf(first, second), 500)
        assertEquals("first", shown.currentId)
        val next = shown.update(listOf(first, second), 30_200)
        assertEquals("second", next.currentId)
        assertEquals(60_200, next.deadlineMillis)
        assertEquals(next, next.dismiss("first"))
    }

    @Test fun `read or withdrawn invitations disappear and inbox suppresses presentation`() {
        val item = invite("one")
        val shown = ConvoyInvitationPresentation(1_000).update(listOf(item), 200)
        assertNull(shown.update(listOf(item.copy(isRead = true)), 500).currentId)
        assertNull(shown.update(emptyList(), 500).currentId)
        val inbox = shown.update(listOf(item), 500, available = false)
        assertNull(inbox.currentId)
        assertNull(inbox.update(listOf(item), 600).currentId)
    }

    @Test fun `unrelated notices and invitations without a convoy id have no direct actions`() {
        val item = invite("one")
        val invalid = listOf(item.copy(category = NotificationCategory.SYSTEM_NOTICE), item.copy(id = "bad", relatedEntityId = null))
        assertNull(ConvoyInvitationPresentation(1_000).update(invalid, 200).currentId)
    }
}
