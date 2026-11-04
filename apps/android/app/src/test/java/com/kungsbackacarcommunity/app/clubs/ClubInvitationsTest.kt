package com.kungsbackacarcommunity.app.clubs

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ClubInvitationsTest {
    @Test fun sentInvitationsRequireIdentityAndOrderedValidDates() {
        val row = mapOf("recipientUid" to "target", "displayName" to "A Friend",
            "createdAt" to "2026-11-01T10:00:00.000Z", "expiresAt" to "2026-11-08T10:00:00.000Z")
        assertEquals("target", parseSentClubInvitation(row)?.recipientUid)
        assertEquals("2026-11-01T10:00:00.000Z", parseSentClubInvitation(row)?.createdAt)
        assertEquals(null, parseSentClubInvitation(row - "recipientUid"))
        assertEquals(null, parseSentClubInvitation(row + ("createdAt" to "invalid")))
        assertEquals(null, parseSentClubInvitation(row + ("expiresAt" to row.getValue("createdAt"))))
    }
    @Test fun onlyUnexpiredUniqueInvitationsRemainActionable() {
        val now = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val pending = ClubInvitation("club", "Klubben", "2026-10-31T12:00:00Z")
        assertEquals(listOf(pending), pendingClubInvitations(listOf(
            pending, pending,
            ClubInvitation("expired", "Gammal", "2026-10-30T12:00:00Z"),
            ClubInvitation("invalid", "Ogiltig", "invalid"),
        ), now))
        assertEquals(emptyList<ClubInvitation>(), pendingClubInvitations(listOf(pending), now + 86_400_000L))
    }
}
