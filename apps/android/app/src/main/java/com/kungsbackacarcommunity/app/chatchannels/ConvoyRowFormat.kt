package com.kungsbackacarcommunity.app.chatchannels

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure presentation helpers for the Convoys tab list — grouping the caller's
 * convoys into ONGOING vs PAST and building each row's member/created-at title.
 *
 * Kept Android-free (java.time + plain data only) so the classification, sorting,
 * name summary, and date formatting are all unit-testable off-device; the
 * composable in [ConvoyListScreen] only turns the results into resource strings.
 */
object ConvoyRowFormat {
    /** Which section a convoy belongs in: still live, or ended history. */
    enum class Phase { ONGOING, PAST }

    /**
     * A convoy is ONGOING while it is `forming` or `active`, and PAST once
     * `ended`. Mirrors the backend's one-convoy-at-a-time rule (ACTIVE_CONVOY_STATUSES
     * in functions/src/convoy/convoy-core.ts): every non-ended accepted convoy is
     * a live one, so the caller has at most one ongoing row and the rest is history.
     */
    fun phase(status: String): Phase = if (status == ENDED_STATUS) Phase.PAST else Phase.ONGOING

    /** The two sections of the list, each newest-created first. */
    data class Grouped(val ongoing: List<ChatConvoy>, val past: List<ChatConvoy>)

    /**
     * Splits [convoys] into the ongoing and past sections, each sorted
     * newest-created first (convoys without a timestamp sort last, stably).
     */
    fun group(convoys: List<ChatConvoy>): Grouped {
        val (ongoing, past) = convoys.partition { phase(it.status) == Phase.ONGOING }
        return Grouped(ongoing = newestFirst(ongoing), past = newestFirst(past))
    }

    private fun newestFirst(convoys: List<ChatConvoy>): List<ChatConvoy> =
        convoys.sortedByDescending { it.createdAtMillis ?: Long.MIN_VALUE }

    /**
     * The member portion of a row title: up to [maxShown] display names, plus a
     * count of everyone beyond them. Blank names are dropped. [shownNames] is
     * empty when no names are available at all, in which case the row falls back
     * to a plain member count.
     */
    data class MemberLabel(val shownNames: List<String>, val overflow: Int)

    fun memberLabel(names: List<String>, maxShown: Int = MAX_NAMES_SHOWN): MemberLabel {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        val shown = clean.take(maxShown)
        return MemberLabel(shownNames = shown, overflow = (clean.size - shown.size).coerceAtLeast(0))
    }

    /**
     * The convoy's created-at instant rendered in the reader's [zone] as a compact
     * "<date> <time>" (e.g. "22 Jul 14:05"). [datePattern] carries the localized
     * month/day order from the localization contract; the time is numeric and
     * follows the device's 12/24-hour setting ([use24Hour]) exactly like
     * ChatDateFormat.time.
     */
    fun createdAtLabel(
        millis: Long,
        zone: ZoneId,
        locale: Locale,
        use24Hour: Boolean,
        datePattern: String,
    ): String {
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        val date = DateTimeFormatter.ofPattern(datePattern, locale).format(moment)
        val timePattern = if (use24Hour) "HH:mm" else "h:mm a"
        val time = DateTimeFormatter.ofPattern(timePattern, locale).format(moment)
        return "$date $time"
    }

    private const val ENDED_STATUS = "ended"
    private const val MAX_NAMES_SHOWN = 2
}
