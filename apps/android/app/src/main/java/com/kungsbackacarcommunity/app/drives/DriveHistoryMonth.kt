package com.kungsbackacarcommunity.app.drives

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * S53 month headings follow the existing newest-first creation-date ordering.
 * A missing timestamp stays undated; changing sort never implies month grouping.
 * Does not sort, filter, fetch or aggregate the tier-visible history page.
 */
internal fun driveHistoryMonth(
    createdAtMillis: Long?,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String? = createdAtMillis?.let {
    SimpleDateFormat("LLLL yyyy", locale).apply { this.timeZone = timeZone }.format(Date(it))
}
