package com.kungsbackacarcommunity.app.drives

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveHistoryMonthTest {
    private val stockholm = TimeZone.getTimeZone("Europe/Stockholm")

    @Test fun missingDateStaysUndated() {
        assertNull(driveHistoryMonth(null, Locale.ENGLISH, stockholm))
    }

    @Test fun monthUsesTheViewersZoneAtTheBoundary() {
        val millis = Instant.parse("2026-09-30T22:30:00Z").toEpochMilli()
        assertEquals("October 2026", driveHistoryMonth(millis, Locale.ENGLISH, stockholm))
        assertEquals("September 2026", driveHistoryMonth(millis, Locale.ENGLISH, TimeZone.getTimeZone("UTC")))
    }

    @Test fun swedishStandaloneMonthAndYearAreLocalized() {
        val millis = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
        assertEquals("oktober 2026", driveHistoryMonth(millis, Locale.forLanguageTag("sv-SE"), stockholm))
    }

    @Test fun repeatedHourAtAutumnDstChangeDoesNotChangeTheMonth() {
        val before = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
        val after = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
        assertEquals(driveHistoryMonth(before, Locale.ENGLISH, stockholm), driveHistoryMonth(after, Locale.ENGLISH, stockholm))
    }
}
