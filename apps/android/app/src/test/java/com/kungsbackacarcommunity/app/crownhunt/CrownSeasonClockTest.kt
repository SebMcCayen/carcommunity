package com.kungsbackacarcommunity.app.crownhunt

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The client season id must agree with the backend's `seasonIdForInstant`
 * (Europe/Stockholm `YYYY-MM`) — a client on the wrong month reads an empty
 * board. The interesting cases are the month boundary as seen through the
 * Stockholm zone offset.
 */
class CrownSeasonClockTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")

    @Test
    fun midMonthSeasonId() {
        // 2026-08-15T12:00Z is comfortably inside August everywhere.
        assertEquals(
            "2026-08",
            CrownSeasonClock.seasonIdForInstant(Instant.parse("2026-08-15T12:00:00Z"), stockholm),
        )
    }

    @Test
    fun justAfterLocalMidnightOnTheFirstIsTheNewMonth() {
        // Sweden is UTC+2 in summer (CEST). 2026-07-31T22:30Z is 2026-08-01 00:30
        // local → already August in the Stockholm zone.
        assertEquals(
            "2026-08",
            CrownSeasonClock.seasonIdForInstant(Instant.parse("2026-07-31T22:30:00Z"), stockholm),
        )
    }

    @Test
    fun justBeforeLocalMidnightIsStillTheOldMonth() {
        // 2026-07-31T21:30Z is 2026-07-31 23:30 local → still July.
        assertEquals(
            "2026-07",
            CrownSeasonClock.seasonIdForInstant(Instant.parse("2026-07-31T21:30:00Z"), stockholm),
        )
    }

    @Test
    fun winterOffsetIsOnlyOneHour() {
        // Sweden is UTC+1 in winter (CET). 2026-01-31T23:30Z is 2026-02-01 00:30
        // local → February.
        assertEquals(
            "2026-02",
            CrownSeasonClock.seasonIdForInstant(Instant.parse("2026-01-31T23:30:00Z"), stockholm),
        )
    }
}
