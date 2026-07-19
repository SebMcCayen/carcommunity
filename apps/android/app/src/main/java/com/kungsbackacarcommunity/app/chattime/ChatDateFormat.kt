package com.kungsbackacarcommunity.app.chattime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a day separator should say. Resolved as DATA so the choice is unit
 * testable; only the caller turns it into a string, because "Today"/"Yesterday"
 * are translated copy that lives in the localization contract.
 */
sealed interface DaySeparatorLabel {
    /** The reader's current local day. */
    data object Today : DaySeparatorLabel

    /** The local day before [Today]. */
    data object Yesterday : DaySeparatorLabel

    /**
     * Any other day, spelled out. [includeYear] is set once the date falls in a
     * different calendar year than today, where a bare "Tuesday, 19 July" is
     * genuinely ambiguous.
     */
    data class Absolute(val date: LocalDate, val includeYear: Boolean) : DaySeparatorLabel
}

/**
 * Date/time formatting for a conversation.
 *
 * Everything locale-VARIABLE (weekday and month names, their order, whether a
 * comma sits between them, whether they are capitalised) comes from the
 * localization contract's per-locale pattern — `chatTime.daySeparatorFormat`,
 * which is `"EEEE, d MMMM"` in English and `"EEEE d MMMM"` in Swedish — fed to
 * `java.time` with the matching [Locale]. So English renders "Tuesday, 19 July"
 * and Swedish renders "tisdag 19 juli", lower-case, because that is what Swedish
 * CLDR data actually contains. Nothing here hardcodes an English format.
 */
object ChatDateFormat {
    /**
     * TODAY and YESTERDAY are deliberately special-cased instead of always
     * printing the weekday and date.
     *
     * They carry the overwhelming majority of a live conversation's reading, and
     * for those two days the calendar date tells the reader nothing they don't
     * already know — while "Today" answers the question they actually have
     * (is this current?) at a glance, with no date arithmetic in their head. It
     * is also the universal convention in messaging apps, so its absence reads as
     * a bug. Every other day still gets the full spelled-out form.
     *
     * [today] is passed in rather than read from the clock so the boundary is
     * testable and so one render cannot straddle midnight inconsistently.
     */
    fun label(date: LocalDate, today: LocalDate): DaySeparatorLabel =
        when (date) {
            today -> DaySeparatorLabel.Today
            today.minusDays(1) -> DaySeparatorLabel.Yesterday
            else -> DaySeparatorLabel.Absolute(date, includeYear = date.year != today.year)
        }

    /** [date] rendered with a locale's own [pattern] (from the localization contract). */
    fun format(date: LocalDate, pattern: String, locale: Locale): String =
        DateTimeFormatter.ofPattern(pattern, locale).format(date)

    /**
     * The per-message time, in the reader's LOCAL zone.
     *
     * The pattern is numeric and locale-independent by design: the only variable
     * part of a time-of-day is the 12/24-hour convention, and on Android that is a
     * per-DEVICE setting the user can flip independently of their locale — so it
     * is passed in ([use24Hour], from `DateFormat.is24HourFormat`) rather than
     * inferred. Day and month NAMES, the part that must never be hardcoded, come
     * from the locale-selected pattern in [format] instead.
     */
    fun time(millis: Long, zone: ZoneId, locale: Locale, use24Hour: Boolean): String {
        val pattern = if (use24Hour) "HH:mm" else "h:mm a"
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        return DateTimeFormatter.ofPattern(pattern, locale).format(time)
    }

    /** The reader's local date for an instant — the day a message belongs under. */
    fun localDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
