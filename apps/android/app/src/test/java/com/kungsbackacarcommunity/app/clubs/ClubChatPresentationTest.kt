package com.kungsbackacarcommunity.app.clubs

import com.kungsbackacarcommunity.app.profile.LiveProfile
import org.junit.Assert.*
import org.junit.Test

class ClubChatPresentationTest {
    @Test fun `retained and live rows share chronological ordering and identity`() {
        val current = ClubMessage("same", "me", "Self", "Current body", 30L)
        val rows = clubChatPresentation(listOf(
            current, current.copy(text = "Outdated retained body"),
            ClubMessage("b", "other", "Other", "Tie b", 10L),
            ClubMessage("a", "other", "Other", "Tie a", 10L),
            ClubMessage("pending", "me", "Self", "Pending timestamp", null),
        ), emptySet())
        assertEquals(listOf("a", "b", "same", "pending"), rows.map { it.id })
        assertEquals("Current body", rows[2].text)
        assertEquals("me", rows[2].senderUid)
        assertEquals(30L, rows[2].createdAtMillis)
    }

    @Test fun `profile hydration matches community including explicit avatar deletion`() {
        val message = ClubMessage("one", "other", "Stored name", "Body", 10L)
        val live = clubChatPresentation(listOf(message), emptySet(),
            mapOf("other" to LiveProfile("Current name", "profileImages/other/current.jpg"))).single()
        assertEquals("Current name", live.senderDisplayName)
        assertEquals("profileImages/other/current.jpg", live.senderAvatarPath)
        val removed = clubChatPresentation(listOf(message), emptySet(),
            mapOf("other" to LiveProfile(null, null))).single()
        assertNull(removed.senderDisplayName)
        assertNull(removed.senderAvatarPath)
        val absent = clubChatPresentation(listOf(message), emptySet()).single()
        assertEquals("Stored name", absent.senderDisplayName)
        assertNull(absent.senderAvatarPath)
        assertEquals(live.copy(senderDisplayName = null, senderAvatarPath = null), removed)
    }

    @Test fun `blocked retained senders remain absent even if their public profile is cached`() {
        val rows = clubChatPresentation(listOf(
            ClubMessage("blocked", "hidden", "Blocked", "Private blocked body", 10L),
            ClubMessage("visible", "friend", "Friend", "Visible body", 20L),
        ), setOf("hidden"), mapOf("hidden" to LiveProfile("Cached blocked name", "profileImages/hidden/a")))
        assertEquals(listOf("visible"), rows.map { it.id })
    }
}
