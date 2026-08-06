package com.kungsbackacarcommunity.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure calendar field-building ([EventCalendar.values]) behind
 * the "Add to calendar" action. The Intent glue is device-verified; this pins the
 * title/location/times and the one-hour reminder.
 */
class EventCalendarTest {
    private fun event(
        title: String = "Cars & Coffee",
        startsAtMillis: Long? = 1_000_000L,
        endsAtMillis: Long? = null,
        locationName: String? = "Torg",
    ) = EventSummary(
        id = "e1",
        title = title,
        summary = null,
        startsAtMillis = startsAtMillis,
        endsAtMillis = endsAtMillis,
        approximateArea = null,
        locationName = locationName,
        latitude = null,
        longitude = null,
        isOfficial = false,
        status = EventStatus.PUBLISHED,
        counts = RsvpCounts.EMPTY,
    )

    @Test
    fun `values carry the title, location, start and end when the event has an explicit end`() {
        val values = EventCalendar.values(event(startsAtMillis = 1_000L, endsAtMillis = 5_000L))!!
        assertEquals("Cars & Coffee", values.title)
        assertEquals("Torg", values.location)
        assertEquals(1_000L, values.startMillis)
        assertEquals(5_000L, values.endMillis)
    }

    @Test
    fun `the reminder is always sixty minutes before the start`() {
        assertEquals(60, EventCalendar.values(event())!!.reminderMinutes)
        assertEquals(60, EventCalendar.REMINDER_MINUTES)
    }

    @Test
    fun `an event with no explicit end gets a default-duration end`() {
        val values = EventCalendar.values(event(startsAtMillis = 2_000L, endsAtMillis = null))!!
        assertEquals(2_000L + EventCalendar.DEFAULT_DURATION_MS, values.endMillis)
    }

    @Test
    fun `a corrupt end before the start falls back to the default duration`() {
        val values = EventCalendar.values(event(startsAtMillis = 10_000L, endsAtMillis = 1_000L))!!
        assertEquals(10_000L + EventCalendar.DEFAULT_DURATION_MS, values.endMillis)
    }

    @Test
    fun `a blank or missing location becomes an empty string`() {
        assertEquals("", EventCalendar.values(event(locationName = null))!!.location)
        assertEquals("", EventCalendar.values(event(locationName = "   "))!!.location)
    }

    @Test
    fun `an event with no start time has no calendar values`() {
        assertNull(EventCalendar.values(event(startsAtMillis = null)))
    }
}
