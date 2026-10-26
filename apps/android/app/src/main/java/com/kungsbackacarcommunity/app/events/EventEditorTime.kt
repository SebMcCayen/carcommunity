package com.kungsbackacarcommunity.app.events

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal enum class EventEndTimeError { NOT_AFTER_START, TOO_LONG }

/** Pure editor time conversion and explicit-end validation. */
internal object EventEditorTime {
    private const val MAX_DURATION_MILLIS = 72L * 60 * 60 * 1000

    /** Null leaves the default end-of-start-day calculation to the server. */
    fun endError(startsAtMillis: Long, endsAtMillis: Long?): EventEndTimeError? {
        if (endsAtMillis == null) return null
        if (endsAtMillis <= startsAtMillis) return EventEndTimeError.NOT_AFTER_START
        // Compare against a bounded sum instead of subtracting arbitrary Longs.
        // Near Long.MAX_VALUE, every representable later end is within 72 hours.
        if (startsAtMillis <= Long.MAX_VALUE - MAX_DURATION_MILLIS &&
            endsAtMillis > startsAtMillis + MAX_DURATION_MILLIS
        ) return EventEndTimeError.TOO_LONG
        return null
    }

    /** Material's date picker represents a calendar date at UTC midnight, not a local instant. */
    fun pickerDate(startsAtMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(startsAtMillis).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Preserve the existing java.time handling of DST gaps/overlaps. */
    fun combine(pickerDateMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long =
        Instant.ofEpochMilli(pickerDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
            .atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
