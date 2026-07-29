package com.kungsbackacarcommunity.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The inbox timestamp tiering: which of "Nu" / "12 min sedan" / "Idag HH:mm" /
 * "Igår HH:mm" / "22 jul HH:mm" a row gets, and the boundaries between them.
 *
 * Every case pins BOTH the instant and `now`, so nothing here depends on the
 * wall clock or on the machine's default zone — the zone is supplied explicitly
 * because a calendar-day decision is meaningless without one.
 */
class NotificationTimeFormatTest {
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")

    /** 29 July 2026, 14:00 local — the "now" every case is measured against. */
    private val now = at(2026, 7, 29, 14, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun label(millis: Long?) = NotificationTimeFormat.label(millis, now, zone)

    // ── Missing timestamp ───────────────────────────────────────────────────

    @Test
    fun aMissingTimestampHasNoLabelAtAll() {
        // `createdAt` is a server timestamp, so an item that was just written is
        // momentarily readable with the field unset. There must be no label to
        // render — not the epoch, which would say "1 jan 1970".
        assertNull(label(null))
    }

    // ── The relative tier ───────────────────────────────────────────────────

    @Test
    fun thisVerySecondIsJustNow() {
        assertEquals(NotificationTimeLabel.JustNow(now), label(now))
    }

    @Test
    fun justUnderAMinuteIsStillJustNow() {
        val millis = now - (NotificationTimeFormat.MINUTE_MILLIS - 1)
        assertEquals(NotificationTimeLabel.JustNow(millis), label(millis))
    }

    @Test
    fun exactlyOneMinuteFlipsToMinutesAgo() {
        val millis = now - NotificationTimeFormat.MINUTE_MILLIS
        assertEquals(NotificationTimeLabel.MinutesAgo(millis, 1), label(millis))
    }

    @Test
    fun minutesAreWholeElapsedMinutes() {
        // 12 minutes and 59 seconds is "12 min", never rounded up to 13: the
        // label must not claim more time has passed than actually has.
        val millis = now - (12 * NotificationTimeFormat.MINUTE_MILLIS + 59_000L)
        assertEquals(NotificationTimeLabel.MinutesAgo(millis, 12), label(millis))
    }

    @Test
    fun justUnderAnHourIsStillMinutes() {
        val millis = now - (NotificationTimeFormat.HOUR_MILLIS - 1)
        assertEquals(NotificationTimeLabel.MinutesAgo(millis, 59), label(millis))
    }

    // ── The absolute tier ───────────────────────────────────────────────────

    @Test
    fun exactlyOneHourFlipsToAClockTime() {
        val millis = now - NotificationTimeFormat.HOUR_MILLIS
        assertEquals(NotificationTimeLabel.Today(millis), label(millis))
    }

    @Test
    fun earlierTodayIsToday() {
        val millis = at(2026, 7, 29, 0, 5)
        assertEquals(NotificationTimeLabel.Today(millis), label(millis))
    }

    @Test
    fun theMinuteBeforeMidnightIsYesterdayNotToday() {
        // The cut is the CALENDAR day in the reader's zone, not "24 hours ago" —
        // 23:59 last night is barely 14 hours old but is unambiguously yesterday.
        val millis = at(2026, 7, 28, 23, 59)
        assertEquals(NotificationTimeLabel.Yesterday(millis), label(millis))
    }

    @Test
    fun theStartOfYesterdayIsStillYesterday() {
        val millis = at(2026, 7, 28, 0, 0)
        assertEquals(NotificationTimeLabel.Yesterday(millis), label(millis))
    }

    @Test
    fun twoDaysBackGetsASpelledOutDateWithoutAYear() {
        val millis = at(2026, 7, 27, 23, 59)
        assertEquals(
            NotificationTimeLabel.Absolute(
                millis = millis,
                date = LocalDate.of(2026, 7, 27),
                includeYear = false,
            ),
            label(millis),
        )
    }

    @Test
    fun anotherCalendarYearAddsTheYear() {
        // "31 dec" alone cannot say which December, so the year comes back —
        // and it is the calendar YEAR that decides, not a 365-day window: this
        // is only seven months old.
        val millis = at(2025, 12, 31, 18, 30)
        assertEquals(
            NotificationTimeLabel.Absolute(
                millis = millis,
                date = LocalDate.of(2025, 12, 31),
                includeYear = true,
            ),
            label(millis),
        )
    }

    // ── Clock skew and zones ────────────────────────────────────────────────

    @Test
    fun aTimestampFromTheFutureReadsAsJustNowRatherThanNegativeMinutes() {
        // The server clock and the device clock are not the same clock, so a
        // freshly written item can carry a timestamp a few seconds ahead of the
        // device. "-1 min sedan" would be nonsense.
        val millis = now + 30_000L
        assertEquals(NotificationTimeLabel.JustNow(millis), label(millis))
    }

    @Test
    fun theDayIsDecidedInTheReadersOwnZone() {
        // 02:00 on the 29th in Stockholm is 17:00 on the 28th in Los Angeles.
        // The same instant is therefore "Idag" for one reader and "Igår" for
        // the other — which is exactly why the zone is a parameter.
        val millis = at(2026, 7, 29, 2, 0)
        assertEquals(
            NotificationTimeLabel.Today(millis),
            NotificationTimeFormat.label(millis, now, zone),
        )
        assertEquals(
            NotificationTimeLabel.Yesterday(millis),
            NotificationTimeFormat.label(millis, now, ZoneId.of("America/Los_Angeles")),
        )
    }
}
