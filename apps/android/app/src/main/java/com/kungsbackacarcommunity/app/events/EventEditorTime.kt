package com.kungsbackacarcommunity.app.events

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Material's date picker represents a calendar date at UTC midnight, not a local instant. */
internal object EventEditorTime {
    fun pickerDate(startsAtMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(startsAtMillis).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Preserve the existing java.time handling of DST gaps/overlaps. */
    fun combine(pickerDateMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long =
        Instant.ofEpochMilli(pickerDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
            .atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
