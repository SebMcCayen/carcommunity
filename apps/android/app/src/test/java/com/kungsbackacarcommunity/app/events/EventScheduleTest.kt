package com.kungsbackacarcommunity.app.events

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventScheduleTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun format(start: String, end: String?) =
        EventSchedule.format(millis(start), end?.let(::millis), Locale.UK, zone)

    @Test
    fun missingOrInvalidEndNeverInventsAnEndTime() {
        assertFalse(format("2026-10-31T17:00:00Z", null).contains("–"))
        assertFalse(format("2026-10-31T17:00:00Z", "2026-10-31T16:00:00Z").contains("–"))
    }

    @Test
    fun sameDayShowsActualEndTimeWithoutRepeatingTheDate() {
        val text = format("2026-10-31T17:00:00Z", "2026-10-31T20:00:00Z")
        assertTrue(text.contains("18:00"))
        assertTrue(text.contains("21:00"))
        assertTrue(text.indexOf("2026") == text.lastIndexOf("2026"))
    }

    @Test
    fun overnightIntervalIncludesBothCalendarDates() {
        val text = format("2026-10-31T22:00:00Z", "2026-11-01T01:00:00Z")
        assertTrue(text.contains("31 Oct 2026"))
        assertTrue(text.contains("1 Nov 2026"))
    }

    @Test
    fun repeatedAutumnHourIncludesBothRealOffsets() {
        val text = format("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z")
        assertTrue(text.contains("+02:00"))
        assertTrue(text.contains("+01:00"))
    }
}
