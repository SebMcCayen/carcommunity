package com.kungsbackacarcommunity.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure check-in domain logic in [EventCheckIn]: the window
 * mirror (must match the server's ±30 min and 4 h default), the eligibility
 * gate, the boundary scheduler and the callable payload.
 */
class EventCheckInTest {

    private val start = 1_000_000_000_000L

    private fun event(
        startsAtMillis: Long? = start,
        endsAtMillis: Long? = null,
        status: EventStatus = EventStatus.PUBLISHED,
        latitude: Double? = 57.48,
        longitude: Double? = 12.07,
    ): EventSummary =
        EventSummary(
            id = "e1",
            title = "Meet",
            summary = null,
            startsAtMillis = startsAtMillis,
            endsAtMillis = endsAtMillis,
            approximateArea = null,
            latitude = latitude,
            longitude = longitude,
            isOfficial = false,
            status = status,
            counts = RsvpCounts.EMPTY,
        )

    @Test
    fun `window pads an explicit end by 30 minutes each side`() {
        val end = start + 60 * 60_000L
        val window = EventCheckIn.window(event(endsAtMillis = end))!!
        assertEquals(start - 30 * 60_000L, window.first)
        assertEquals(end + 30 * 60_000L, window.last)
    }

    @Test
    fun `window uses the 4h default when there is no explicit end`() {
        val window = EventCheckIn.window(event(endsAtMillis = null))!!
        assertEquals(start - 30 * 60_000L, window.first)
        assertEquals(start + 4 * 60 * 60_000L + 30 * 60_000L, window.last)
    }

    @Test
    fun `window is null without a start`() {
        assertNull(EventCheckIn.window(event(startsAtMillis = null)))
    }

    @Test
    fun `isWindowOpen is true inside and false on either side`() {
        val e = event(endsAtMillis = start + 60 * 60_000L)
        assertFalse(EventCheckIn.isWindowOpen(e, start - 31 * 60_000L))
        assertTrue(EventCheckIn.isWindowOpen(e, start))
        assertTrue(EventCheckIn.isWindowOpen(e, start - 29 * 60_000L))
        assertFalse(EventCheckIn.isWindowOpen(e, start + 60 * 60_000L + 31 * 60_000L))
    }

    @Test
    fun `canCheckIn requires member, a checkinable status, a pin and an open window`() {
        val nowInside = start
        assertTrue(EventCheckIn.canCheckIn(true, event(), nowInside))
        // COMPLETED is still checkinable in-window — the server accepts it too, so
        // a member on-site when the event auto-completes can still prove attendance.
        assertTrue(EventCheckIn.canCheckIn(true, event(status = EventStatus.COMPLETED), nowInside))
        // Not a member.
        assertFalse(EventCheckIn.canCheckIn(false, event(), nowInside))
        // Cancelled/draft are never checkinable.
        assertFalse(EventCheckIn.canCheckIn(true, event(status = EventStatus.CANCELLED), nowInside))
        assertFalse(EventCheckIn.canCheckIn(true, event(status = EventStatus.DRAFT), nowInside))
        // No pin.
        assertFalse(EventCheckIn.canCheckIn(true, event(latitude = null, longitude = null), nowInside))
        // Window closed (even for a completed event).
        assertFalse(EventCheckIn.canCheckIn(true, event(), start + 10 * 60 * 60_000L))
        assertFalse(
            EventCheckIn.canCheckIn(
                true,
                event(status = EventStatus.COMPLETED),
                start + 10 * 60 * 60_000L,
            ),
        )
    }

    @Test
    fun `nextWindowBoundary is the open edge before, the close edge during, null after`() {
        val e = event(endsAtMillis = start + 60 * 60_000L)
        val open = start - 30 * 60_000L
        val close = start + 60 * 60_000L + 30 * 60_000L
        assertEquals(open, EventCheckIn.nextWindowBoundaryMillis(e, start - 60 * 60_000L))
        assertEquals(close, EventCheckIn.nextWindowBoundaryMillis(e, start))
        assertNull(EventCheckIn.nextWindowBoundaryMillis(e, close + 1))
    }

    @Test
    fun `payload sends the fix and always reports the mock flag`() {
        val fix =
            CheckInFix(
                latitude = 57.48,
                longitude = 12.07,
                accuracyMeters = 8.0,
                capturedAtMillis = start,
                isMock = false,
            )
        val payload = EventCheckIn.checkInPayload("e1", fix)
        assertEquals("e1", payload["eventId"])
        assertEquals(57.48, payload["latitude"])
        assertEquals(12.07, payload["longitude"])
        assertEquals(8.0, payload["accuracyMeters"])
        assertEquals(false, payload["isMockLocation"])
        // capturedAt is ISO-8601 UTC.
        assertEquals(Events.toIsoUtc(start), payload["capturedAt"])
    }

    @Test
    fun `payload omits accuracy when absent but keeps the mock flag`() {
        val fix =
            CheckInFix(
                latitude = 57.48,
                longitude = 12.07,
                accuracyMeters = null,
                capturedAtMillis = start,
                isMock = true,
            )
        val payload = EventCheckIn.checkInPayload("e1", fix)
        assertFalse(payload.containsKey("accuracyMeters"))
        assertEquals(true, payload["isMockLocation"])
    }

    @Test
    fun `attendance checkedIn reflects a sample or verification`() {
        assertFalse(EventAttendanceStatus(verified = false, sampleCount = 0).checkedIn)
        assertTrue(EventAttendanceStatus(verified = false, sampleCount = 1).checkedIn)
        assertTrue(EventAttendanceStatus(verified = true, sampleCount = 0).checkedIn)
    }
}
