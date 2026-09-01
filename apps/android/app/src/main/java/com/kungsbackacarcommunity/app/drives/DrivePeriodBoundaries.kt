package com.kungsbackacarcommunity.app.drives

import java.util.Calendar

/**
 * Local-time-zone period boundaries ("start of this week / this month" as
 * epoch-millis), shared by every drives surface that resolves a [DriveDateRange]
 * to a cut-off.
 *
 * This is the ONE place the `Calendar` truncation lives, so the History filter
 * ([DrivesScreen]) and the personal stats fold ([DriveStatsScreen]) can never
 * silently diverge on DST / locale first-day-of-week edge cases. The Calendar /
 * time-zone concern deliberately stays here at the composable edge; the folds it
 * feeds ([DriveFilters], [DriveStatsCalculator]) take plain [Long]s and stay
 * pure and deterministic.
 *
 * Both functions read the device clock, default time zone and default locale on
 * each call, so a month/week rollover corrects itself on the next recomposition.
 */
object DrivePeriodBoundaries {
    /** Start of the current calendar month (local time zone) as epoch-millis. */
    fun startOfCurrentMonthMillis(): Long =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Start of the NEXT calendar month (local time zone) as epoch-millis — the
     * half-open upper bound of the current month, so [startOfCurrentMonthMillis]
     * ..[startOfNextMonthMillis] is exactly one calendar month. Used as the
     * `monthEndMillis` sent to `drives-stats`, which requires the pair to straddle
     * server time and span 27–32 days (a full calendar month always does).
     */
    fun startOfNextMonthMillis(): Long =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, 1)
        }.timeInMillis

    /**
     * Start of the current week (local time zone, honouring the locale's first
     * day of week) as epoch-millis. Truncates to midnight, then steps back to
     * the week's first day so it is correct regardless of today's position in
     * the week.
     */
    fun startOfCurrentWeekMillis(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val delta = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
            add(Calendar.DAY_OF_YEAR, -delta)
        }.timeInMillis
}
