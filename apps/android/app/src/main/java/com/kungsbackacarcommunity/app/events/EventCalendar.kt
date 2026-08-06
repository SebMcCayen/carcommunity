package com.kungsbackacarcommunity.app.events

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

/**
 * "Add this event to my calendar" — an Intent-based hand-off to the phone's own
 * calendar app, pre-filled with the event's title, location and times plus a
 * reminder ONE HOUR before it starts.
 *
 * Deliberately Intent-based (`Intent.ACTION_INSERT` on
 * [CalendarContract.Events.CONTENT_URI]): the user's calendar app opens with the
 * fields filled in and the user confirms the save, so the app needs NO
 * `WRITE_CALENDAR` runtime permission and never touches the calendar provider
 * directly. The pure field-building ([values]) is JVM-unit-testable; only the
 * [launch] glue (Intent + startActivity) is device-verified.
 */
object EventCalendar {
    /** The reminder lead time the task requires: 60 minutes (one hour) before start. */
    const val REMINDER_MINUTES = 60

    /**
     * Assumed duration when an event carries a start but no explicit end, so the
     * calendar entry always gets a sensible DTEND rather than a zero-length slot.
     * Two hours — a typical car-meet length, and shorter than the check-in default
     * on purpose (a calendar block should not over-claim the member's evening).
     */
    const val DEFAULT_DURATION_MS = 2L * 60L * 60_000L

    /**
     * The pure, Android-free field set for a calendar insert. [endMillis] is the
     * event's explicit end, else [startMillis] + [DEFAULT_DURATION_MS]; [location]
     * is the place name (blank when the event has none); [reminderMinutes] is always
     * [REMINDER_MINUTES]. Null when the event has no readable start — with no start
     * there is nothing to schedule, and the caller hides the action.
     */
    data class CalendarValues(
        val title: String,
        val location: String,
        val startMillis: Long,
        val endMillis: Long,
        val reminderMinutes: Int,
    )

    /**
     * Builds the [CalendarValues] for [event], or null when it has no start time.
     * The end defaults to start + [DEFAULT_DURATION_MS] when the event has no
     * explicit end; a location name that is null/blank becomes an empty string so
     * the intent extra is always a valid (possibly empty) value.
     */
    fun values(event: EventSummary): CalendarValues? {
        val start = event.startsAtMillis ?: return null
        val end = event.endsAtMillis ?: (start + DEFAULT_DURATION_MS)
        return CalendarValues(
            title = event.title,
            location = event.locationName?.takeIf { it.isNotBlank() }.orEmpty(),
            startMillis = start,
            // Guard a corrupt end that precedes the start (an event may carry a bad
            // pair): fall back to the default-duration slot rather than a negative
            // one the calendar app would reject.
            endMillis = if (end >= start) end else start + DEFAULT_DURATION_MS,
            reminderMinutes = REMINDER_MINUTES,
        )
    }

    /**
     * The pre-filled `ACTION_INSERT` intent for [values]. Sets TITLE, EVENT_LOCATION,
     * the begin/end times, and asks the calendar app for a reminder [REMINDER_MINUTES]
     * before the start (HAS_ALARM + the reminder-minutes extra — the values the
     * common calendar apps honour on an insert). Kept internal + pure of side effects
     * so [launch] and a test can both build it.
     */
    fun buildInsertIntent(values: CalendarValues): Intent =
        Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, values.title)
            putExtra(CalendarContract.Events.EVENT_LOCATION, values.location)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, values.startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, values.endMillis)
            // Ask for a reminder one hour before. HAS_ALARM flags the event as
            // reminded; the minutes extra carries the lead time. Calendar apps that
            // ignore the minutes still create a reminded event the user can adjust.
            putExtra(CalendarContract.Events.HAS_ALARM, 1)
            putExtra(CalendarContract.Reminders.MINUTES, values.reminderMinutes)
        }

    /**
     * Opens the phone's calendar app pre-filled with [event] (title, location,
     * times) and a one-hour reminder. Invokes [onUnavailable] when the event has no
     * start time to schedule OR no calendar app can handle the insert, so the caller
     * can explain rather than fail silently.
     */
    fun launch(
        context: Context,
        event: EventSummary,
        onUnavailable: () -> Unit,
    ) {
        val values = values(event)
        if (values == null) {
            onUnavailable()
            return
        }
        val intent = buildInsertIntent(values)
        // A non-Activity context has no task to launch into; add the flag so this
        // helper is safe for any Context (mirrors ExternalNavigation.launch).
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}
