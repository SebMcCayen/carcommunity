package com.kungsbackacarcommunity.app.clubs

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ClubInvitationsTest {
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
