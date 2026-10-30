package com.kungsbackacarcommunity.app.clubs

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

class ClubContractTest {
    @Test fun `report reasons map to the existing Driver moderation schema`() {
        val mapped = com.kungsbackacarcommunity.app.chat.ChatReportReason.entries.map(::clubReportReason).toSet()
        assertEquals(setOf("spam", "harassment", "hate", "dangerousAdvice", "privacy", "other"), mapped)
    }
    @Test fun `unknown role and malformed membership epoch fail closed`() {
        assertNull(parseMembership(mapOf("clubId" to "club", "role" to "admin", "joinedAt" to "2026-10-27T10:00:00Z")))
        assertNull(parseMembership(mapOf("clubId" to "club", "role" to "member", "joinedAt" to "yesterday")))
        assertEquals(ClubRole.Moderator, parseMembership(mapOf("clubId" to "club", "role" to "moderator", "joinedAt" to "2026-10-27T10:00:00Z"))?.role)
    }
    @Test fun `profile and directory parsing ignore invalid images and nonfinite stats`() {
        val club = parseClub(mapOf("id" to "a", "name" to "Club", "logoUrl" to "javascript:alert(1)",
            "stats" to mapOf("driveCount" to -1, "distanceMeters" to Double.NaN, "durationSeconds" to -50)))!!
        assertNull(club.logoUrl)
        assertEquals(ClubStats(), club.stats)
        assertNull(safeClubImage("https://user:password@example.com/image.jpg"))
        assertNull(safeClubImage("file:///tmp/image.jpg"))
        assertEquals("https://example.com/image.jpg", safeClubImage("https://example.com/image.jpg"))
        assertNull(parseClub(mapOf("id" to "a")))
    }
    @Test fun `cooldown expires at exact server deadline and legacy conflict still blocks`() {
        val deadline = "2026-10-28T10:00:00Z"
        val now = Instant.parse(deadline).toEpochMilli()
        assertTrue(clubCooldownActive(ClubState(joinAllowedAt = deadline), now - 1))
        assertFalse(clubCooldownActive(ClubState(joinAllowedAt = deadline), now))
        assertTrue(clubCooldownActive(ClubState(joinAllowedAt = deadline, legacyConflict = true), now + 1))
    }
    @Test fun `moderator cannot remove owner other moderator or self`() {
        val owner = ClubMember("owner", "Owner", ClubRole.Owner)
        val moderator = ClubMember("otherMod", "Mod", ClubRole.Moderator)
        val member = ClubMember("member", "Member", ClubRole.Member)
        assertFalse(ClubRole.Moderator.canRemove("me", owner))
        assertFalse(ClubRole.Moderator.canRemove("me", moderator))
        assertFalse(ClubRole.Moderator.canRemove("member", member))
        assertTrue(ClubRole.Moderator.canRemove("me", member))
        assertFalse(ClubRole.Member.canRemove("me", member))
    }
    @Test fun `calendar includes every leap day and starts on Monday without shifting event dates`() {
        val cells = clubCalendarDays(YearMonth.of(2028, 2))
        assertEquals(0, cells.size % 7)
        assertNull(cells[0]) // 1 February 2028 is Tuesday.
        assertEquals(29, cells.filterNotNull().size)
        assertEquals(29, cells.filterNotNull().last().dayOfMonth)
        assertEquals(6, clubCalendarDays(YearMonth.of(2026, 11)).indexOfFirst { it?.dayOfMonth == 1 })
    }
    @Test fun `malformed invitations and private data are not rendered as real clubs`() {
        val state = parseClubState(mapOf("club" to listOf("bad"), "invitations" to listOf(null, mapOf("clubName" to "No id"))))
        assertNull(state.club)
        assertTrue(state.invitations.isEmpty())
    }

    @Test fun `chat cooldown details survive Club mapping while auth and session denial win`() {
        val fields = mapOf("reason" to "chat-rate-limited", "retryAfterMs" to 30_000L,
            "blockedUntilMillis" to 100_000L, "serverNowMillis" to 70_000L)
        val failure = clubCallableFailure("RESOURCE_EXHAUSTED", fields, true)
        assertEquals("chat-rate-limited", failure.code)
        assertEquals(30_000L, failure.cooldown?.retryAfterMs)
        assertEquals(100_000L, failure.cooldown?.blockedUntilMillis)
        for ((code, current) in listOf("UNAUTHENTICATED" to true, "PERMISSION_DENIED" to true, "RESOURCE_EXHAUSTED" to false)) {
            val denied = clubCallableFailure(code, fields, current)
            assertNull(denied.cooldown)
            assertTrue(denied.code in setOf("unauthenticated", "permission-denied"))
        }
    }
}
