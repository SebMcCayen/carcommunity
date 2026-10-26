package com.kungsbackacarcommunity.app.events

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class EventEditorTimeTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun pickerUsesLocalCalendarDate_evenWhenUtcDateIsThePreviousDay() {
        assertEquals(
            millis("2026-07-01T00:00:00Z"),
            EventEditorTime.pickerDate(millis("2026-06-30T22:30:00Z"), stockholm),
        )
    }

    @Test
    fun pickerUsesLocalCalendarDate_westOfUtcToo() {
        assertEquals(
            millis("2026-06-30T00:00:00Z"),
            EventEditorTime.pickerDate(millis("2026-07-01T01:30:00Z"), ZoneId.of("America/Los_Angeles")),
        )
    }

    @Test
    fun editRoundTripPreservesLocalDateAndTime_inSummerAndWinter() {
        listOf("2026-07-01T22:30:00Z", "2026-12-01T23:30:00Z").forEach { iso ->
            val original = millis(iso)
            assertEquals(original, EventEditorTime.combine(EventEditorTime.pickerDate(original, stockholm), 0, 30, stockholm))
        }
    }

    @Test
    fun springGapUsesExistingJavaTimeForwardAdjustment() {
        assertEquals(
            millis("2026-03-29T01:30:00Z"),
            EventEditorTime.combine(millis("2026-03-29T00:00:00Z"), 2, 30, stockholm),
        )
    }

    @Test
    fun autumnOverlapUsesExistingEarlierOffset() {
        assertEquals(
            millis("2026-10-25T00:30:00Z"),
            EventEditorTime.combine(millis("2026-10-25T00:00:00Z"), 2, 30, stockholm),
        )
    }
}
