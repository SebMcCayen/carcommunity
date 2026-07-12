package com.kungsbackacarcommunity.app.navigation.turnbyturn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class NavProgressFormatTest {
    private lateinit var previous: Locale

    @Before
    fun pinLocale() {
        // remaining() delegates to NavFormat, whose distance uses the default
        // locale's decimal separator; pin US so "%.1f" is a dot for assertions.
        previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previous)
    }

    @Test
    fun `arrival clock adds remaining duration in 24h`() {
        // 2024-01-01T13:00:00Z + 12 min = 13:12 in UTC.
        val now = Instant.parse("2024-01-01T13:00:00Z")
        assertEquals("13:12", NavProgressFormat.arrivalClock(12 * 60.0, now, ZoneOffset.UTC))
    }

    @Test
    fun `arrival clock respects the zone offset`() {
        val now = Instant.parse("2024-01-01T13:00:00Z")
        // +2h zone → 15:00 local, plus 30 min → 15:30.
        val zone = ZoneOffset.ofHours(2)
        assertEquals("15:30", NavProgressFormat.arrivalClock(30 * 60.0, now, zone))
    }

    @Test
    fun `arrival clock past midnight wraps`() {
        val now = Instant.parse("2024-01-01T23:50:00Z")
        assertEquals("00:05", NavProgressFormat.arrivalClock(15 * 60.0, now, ZoneOffset.UTC))
    }

    @Test
    fun `negative remaining duration clamps to now`() {
        val now = Instant.parse("2024-01-01T08:00:00Z")
        assertEquals("08:00", NavProgressFormat.arrivalClock(-500.0, now, ZoneOffset.UTC))
    }

    @Test
    fun `remaining summary is time then distance`() {
        val progress =
            NavProgress(distanceRemainingMeters = 4523.0, durationRemainingSeconds = 12 * 60.0)
        assertEquals("12 min · 4.5 km", NavProgressFormat.remaining(progress, "m", "km", "min", "h"))
    }

    @Test
    fun `remaining summary uses metres under a kilometre`() {
        val progress =
            NavProgress(distanceRemainingMeters = 320.0, durationRemainingSeconds = 90.0)
        assertEquals("2 min · 320 m", NavProgressFormat.remaining(progress, "m", "km", "min", "h"))
    }
}
