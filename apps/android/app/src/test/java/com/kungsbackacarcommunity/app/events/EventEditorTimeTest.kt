package com.kungsbackacarcommunity.app.events

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventEditorTimeTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun absentEndLeavesDefaultCalculationToTheServer() {
        // Even a start at the final millisecond of its Stockholm day is left to
        // the server; the client only validates explicitly chosen ends.
        assertNull(EventEditorTime.endError(millis("2026-07-01T21:59:59.999Z"), null))
        assertNull(EventEditorTime.endError(Long.MAX_VALUE, null))
    }

    @Test
    fun explicitEndMustBeStrictlyAfterStart() {
        val start = millis("2026-07-01T18:00:00Z")
        assertEquals(EventEndTimeError.NOT_AFTER_START, EventEditorTime.endError(start, start - 1))
        assertEquals(EventEndTimeError.NOT_AFTER_START, EventEditorTime.endError(start, start))
        assertNull(EventEditorTime.endError(start, start + 1))
    }

    @Test
    fun explicitEndAllowsExactly72ElapsedHoursButNoMore() {
        val start = millis("2026-07-01T18:00:00Z")
        val limit = start + 72L * 60 * 60 * 1000
        assertNull(EventEditorTime.endError(start, limit))
        assertEquals(EventEndTimeError.TOO_LONG, EventEditorTime.endError(start, limit + 1))
    }

    @Test
    fun overnightEndIsValid() {
        val start = EventEditorTime.combine(millis("2026-07-01T00:00:00Z"), 23, 30, stockholm)
        val end = EventEditorTime.combine(millis("2026-07-02T00:00:00Z"), 0, 30, stockholm)
        assertNull(EventEditorTime.endError(start, end))
        assertEquals(3_600_000L, end - start)
    }

    @Test
    fun springDstLimitUsesElapsedHoursRatherThanThreeCalendarDays() {
        val start = EventEditorTime.combine(millis("2026-03-28T00:00:00Z"), 12, 0, stockholm)
        val threeDaysLater = EventEditorTime.combine(millis("2026-03-31T00:00:00Z"), 12, 0, stockholm)
        assertEquals(71L * 3_600_000, threeDaysLater - start)
        assertNull(EventEditorTime.endError(start, threeDaysLater))
        assertNull(EventEditorTime.endError(start, threeDaysLater + 3_600_000))
        assertEquals(EventEndTimeError.TOO_LONG, EventEditorTime.endError(start, threeDaysLater + 3_600_001))
    }

    @Test
    fun autumnDstLimitUsesElapsedHoursRatherThanThreeCalendarDays() {
        val start = EventEditorTime.combine(millis("2026-10-24T00:00:00Z"), 12, 0, stockholm)
        val threeDaysLater = EventEditorTime.combine(millis("2026-10-27T00:00:00Z"), 12, 0, stockholm)
        assertEquals(73L * 3_600_000, threeDaysLater - start)
        assertEquals(EventEndTimeError.TOO_LONG, EventEditorTime.endError(start, threeDaysLater))
        assertNull(EventEditorTime.endError(start, threeDaysLater - 3_600_000))
    }

    @Test
    fun endValidationDoesNotOverflowAtLongBoundaries() {
        val duration = 72L * 60 * 60 * 1000
        assertEquals(EventEndTimeError.TOO_LONG, EventEditorTime.endError(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(EventEndTimeError.TOO_LONG, EventEditorTime.endError(Long.MIN_VALUE, 0L))
        assertEquals(EventEndTimeError.NOT_AFTER_START, EventEditorTime.endError(Long.MAX_VALUE, Long.MIN_VALUE))
        assertNull(EventEditorTime.endError(Long.MIN_VALUE, Long.MIN_VALUE + duration))
        assertEquals(EventEndTimeError.TOO_LONG, EventEditorTime.endError(Long.MIN_VALUE, Long.MIN_VALUE + duration + 1))
        assertNull(EventEditorTime.endError(Long.MAX_VALUE - duration, Long.MAX_VALUE))
        assertNull(EventEditorTime.endError(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertEquals(EventEndTimeError.TOO_LONG, EventEditorTime.endError(Long.MAX_VALUE - duration - 1, Long.MAX_VALUE))
    }

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
