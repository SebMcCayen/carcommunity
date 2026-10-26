package com.kungsbackacarcommunity.app.events

import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Localized event interval from actual timestamps; no invented default duration. */
internal object EventSchedule {
    fun format(start: Long, end: Long?, locale: Locale, zone: ZoneId): String {
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .apply { timeZone = TimeZone.getTimeZone(zone) }
        val startText = dateTime.format(Date(start))
        if (end == null || end <= start) return startText

        val localStart = Instant.ofEpochMilli(start).atZone(zone)
        val localEnd = Instant.ofEpochMilli(end).atZone(zone)
        val endFormat = if (localStart.toLocalDate() == localEnd.toLocalDate()) {
            DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = TimeZone.getTimeZone(zone) }
        } else dateTime
        val endText = endFormat.format(Date(end))
        // A repeated local hour at autumn DST must not look like a zero-length
        // event. Explicit offsets make the actual interval unambiguous.
        return if (localStart.offset != localEnd.offset) {
            val offset = DateTimeFormatter.ofPattern("XXX", locale)
            "$startText ${offset.format(localStart)} – $endText ${offset.format(localEnd)}"
        } else "$startText – $endText"
    }
}
