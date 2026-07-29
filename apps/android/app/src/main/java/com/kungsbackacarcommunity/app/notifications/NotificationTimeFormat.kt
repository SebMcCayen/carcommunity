package com.kungsbackacarcommunity.app.notifications

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What one inbox row's timestamp should say. Resolved as DATA rather than as a
 * string — the same split
 * [com.kungsbackacarcommunity.app.chattime.DaySeparatorLabel] uses — so the
 * tiering is unit-testable while the words ("Idag", "5 min sedan") stay where
 * translated copy belongs: the localization contract.
 *
 * Every case carries the [millis] it describes, so a caller that has a label at
 * all has everything it needs to render one; whether there is a timestamp to
 * show is decided ONCE, by [NotificationTimeFormat.label] returning null.
 */
sealed interface NotificationTimeLabel {
    /** The instant this label describes, for rendering the clock time. */
    val millis: Long

    /** Arrived less than a minute ago. */
    data class JustNow(override val millis: Long) : NotificationTimeLabel

    /** Arrived under an hour ago, in whole elapsed minutes (1..59). */
    data class MinutesAgo(override val millis: Long, val minutes: Int) : NotificationTimeLabel

    /** Arrived earlier on the reader's current local day; shown with a clock time. */
    data class Today(override val millis: Long) : NotificationTimeLabel

    /** Arrived on the local day before today; shown with a clock time. */
    data class Yesterday(override val millis: Long) : NotificationTimeLabel

    /**
     * Any other day, spelled out with a clock time. [includeYear] is set once
     * the date falls in a different calendar year than today, where a bare
     * "22 jul" is genuinely ambiguous.
     */
    data class Absolute(
        override val millis: Long,
        val date: LocalDate,
        val includeYear: Boolean,
    ) : NotificationTimeLabel
}

/**
 * When an inbox row arrived.
 *
 * TIERED, not one format for everything. Seb asked for "a date and time stamp on
 * all notification messages", and every row does get one — but a raw
 * "29 jul 14:05" on a notification that landed four minutes ago makes the reader
 * do clock arithmetic to answer the only question they actually have about a
 * fresh item ("is this new?"). So the first hour is relative, and from an hour
 * old onwards every row carries a real clock time, with a real date attached the
 * moment "today" stops identifying the day on its own:
 *
 *  - under a minute  → "Nu"
 *  - under an hour   → "12 min sedan"
 *  - earlier today   → "Idag 09:12"
 *  - yesterday       → "Igår 22:40"
 *  - anything older  → "22 jul 14:05"  (plus the year in a different year)
 *
 * That is the ordinary inbox convention, and it keeps the promise that anything
 * not from the last hour is shown as an absolute time, with an absolute DATE as
 * soon as the day is no longer obvious.
 *
 * Pure and Android-free (java.time + plain data), so every boundary above is
 * testable off-device. [now] is passed in rather than read from the clock, which
 * is what makes those cuts pinnable in a test and stops one render from
 * straddling a boundary inconsistently. The reader's [ZoneId] decides which
 * calendar day an instant belongs to, because the timestamp is stored as an
 * absolute instant and only a zone turns that into a day.
 */
object NotificationTimeFormat {
    /**
     * The label for an item created at [createdAtMillis], or null when there is
     * no timestamp to show.
     *
     * NULL IS A REAL CASE, not defensive padding: `createdAt` is a server
     * timestamp, so a locally-echoed write is momentarily readable with the
     * field still unset. The caller renders nothing at all for null — never a
     * placeholder, and never the epoch, which would claim the notification
     * arrived in 1970.
     *
     * A timestamp slightly AHEAD of [now] is normal too — the server clock and
     * the device clock are not the same clock — and collapses to
     * [NotificationTimeLabel.JustNow] rather than counting backwards into
     * negative minutes.
     */
    fun label(createdAtMillis: Long?, now: Long, zone: ZoneId): NotificationTimeLabel? {
        val millis = createdAtMillis ?: return null
        val elapsed = now - millis
        if (elapsed < MINUTE_MILLIS) return NotificationTimeLabel.JustNow(millis)
        if (elapsed < HOUR_MILLIS) {
            return NotificationTimeLabel.MinutesAgo(millis, (elapsed / MINUTE_MILLIS).toInt())
        }
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date) {
            today -> NotificationTimeLabel.Today(millis)
            today.minusDays(1) -> NotificationTimeLabel.Yesterday(millis)
            else ->
                NotificationTimeLabel.Absolute(
                    millis = millis,
                    date = date,
                    includeYear = date.year != today.year,
                )
        }
    }

    /**
     * One minute, in milliseconds — the cut between
     * [NotificationTimeLabel.JustNow] and [NotificationTimeLabel.MinutesAgo].
     */
    const val MINUTE_MILLIS = 60_000L

    /** One hour, in milliseconds — where relative gives way to a clock time. */
    const val HOUR_MILLIS = 60 * MINUTE_MILLIS
}
