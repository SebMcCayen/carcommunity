package com.kungsbackacarcommunity.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTest {

    @Test
    fun `status and rsvp enums parse known wire values`() {
        assertEquals(EventStatus.PUBLISHED, EventStatus.fromWire("published"))
        assertEquals(EventStatus.CANCELLED, EventStatus.fromWire("cancelled"))
        assertNull(EventStatus.fromWire("archived"))
        assertEquals(RsvpStatus.GOING, RsvpStatus.fromWire("going"))
        assertEquals(RsvpStatus.NOT_GOING, RsvpStatus.fromWire("not_going"))
        assertNull(RsvpStatus.fromWire("interested"))
    }

    @Test
    fun `rsvp counts read defensively from a map`() {
        val counts = RsvpCounts.fromMap(mapOf("going" to 5L, "maybe" to 2, "not_going" to -3))
        assertEquals(5, counts.going)
        assertEquals(2, counts.maybe)
        assertEquals(0, counts.notGoing) // negative clamped
        assertEquals(7, counts.total)
        assertEquals(RsvpCounts.EMPTY, RsvpCounts.fromMap(null))
        assertEquals(0, RsvpCounts.fromMap(mapOf("going" to "oops")).going)
    }

    @Test
    fun `canRsvp requires passing the member gate on a published event`() {
        assertTrue(Events.canRsvp(passesMemberGate = true, status = EventStatus.PUBLISHED))
        assertFalse(Events.canRsvp(passesMemberGate = false, status = EventStatus.PUBLISHED))
        assertFalse(Events.canRsvp(passesMemberGate = true, status = EventStatus.CANCELLED))
        assertFalse(Events.canRsvp(passesMemberGate = true, status = EventStatus.COMPLETED))
    }

    @Test
    fun `canSeeDetails mirrors the member-gated published rule`() {
        assertTrue(Events.canSeeDetails(true, EventStatus.PUBLISHED))
        assertFalse(Events.canSeeDetails(false, EventStatus.PUBLISHED))
        assertFalse(Events.canSeeDetails(true, EventStatus.DRAFT))
    }

    @Test
    fun `sortedForList orders soonest first with nulls last`() {
        val a = event("a", 300L)
        val b = event("b", 100L)
        val c = event("c", null)
        val d = event("d", 200L)
        val sorted = Events.sortedForList(listOf(a, b, c, d)).map { it.id }
        assertEquals(listOf("b", "d", "a", "c"), sorted)
    }

    @Test
    fun `published events query limit is two hundred`() {
        assertEquals(200L, Events.PUBLISHED_EVENTS_QUERY_LIMIT)
    }

    @Test
    fun `isValidForCreate enforces required fields and bounds`() {
        val ok = createInput()
        assertTrue(Events.isValidForCreate(ok))
        assertFalse(Events.isValidForCreate(ok.copy(title = "   ")))
        assertFalse(Events.isValidForCreate(ok.copy(approximateArea = "")))
        assertFalse(Events.isValidForCreate(ok.copy(title = "x".repeat(201))))
        assertFalse(Events.isValidForCreate(ok.copy(endsAtMillis = ok.startsAtMillis - 1)))
        assertTrue(Events.isValidForCreate(ok.copy(endsAtMillis = ok.startsAtMillis + 1)))
    }

    @Test
    fun `toIsoUtc emits a whole-second UTC instant`() {
        // 2026-07-11T18:30:00Z with a stray 750ms that must be truncated.
        assertEquals("2026-07-11T18:30:00Z", Events.toIsoUtc(1_783_794_600_750L))
    }

    @Test
    fun `createPayload includes required fields and drops blank optionals`() {
        val payload =
            Events.createPayload(
                createInput().copy(
                    summary = "  hi  ",
                    description = "   ",
                    endsAtMillis = 1_783_794_600_000L + 3_600_000L,
                ),
            )
        assertEquals("Cars & Coffee", payload["title"])
        assertEquals("Kungsbacka", payload["approximateArea"])
        assertEquals("2026-07-11T18:30:00Z", payload["startsAt"])
        assertEquals("hi", payload["summary"]) // trimmed
        assertFalse(payload.containsKey("description")) // blank dropped
        assertTrue(payload.containsKey("endsAt"))
        assertFalse(payload.containsKey("locationName"))
    }

    @Test
    fun `past list is most recent first`() {
        val sorted =
            Events.sortedForPastList(
                listOf(
                    event("old", 1_000L),
                    event("newest", 3_000L),
                    event("middle", 2_000L),
                ),
            )
        assertEquals(listOf("newest", "middle", "old"), sorted.map { it.id })
    }

    @Test
    fun `past list is the reverse of the upcoming order, not the same order`() {
        // Pins the direction itself: a past view that reused sortedForList
        // would show the oldest event at the top of the archive.
        val events = listOf(event("a", 1_000L), event("b", 2_000L), event("c", 3_000L))
        val upcoming = Events.sortedForList(events).map { it.id }
        val past = Events.sortedForPastList(events).map { it.id }
        assertEquals(upcoming.reversed(), past)
    }

    @Test
    fun `an event with no start time sorts last in the past list, not first`() {
        // nullsLast wraps the DESCENDING comparator. Reversing the ascending
        // comparator instead would float the unknown-time event to the top of
        // the archive and claim it is the most recent thing that happened.
        val sorted =
            Events.sortedForPastList(
                listOf(
                    event("unknown", null),
                    event("older", 1_000L),
                    event("newer", 2_000L),
                ),
            )
        assertEquals(listOf("newer", "older", "unknown"), sorted.map { it.id })
    }

    private fun createInput() =
        CreateEventInput(
            title = "Cars & Coffee",
            approximateArea = "Kungsbacka",
            startsAtMillis = 1_783_794_600_000L,
        )

    // ---- Coordinate validity (location picker capture) ----------------------

    @Test
    fun `coordinate pair is valid only when both present and in range`() {
        assertTrue(Events.isValidCoordinatePair(57.4874, 12.0757))
        assertTrue(Events.isValidCoordinatePair(null, null)) // no pin
        assertFalse(Events.isValidCoordinatePair(57.0, null)) // half set
        assertFalse(Events.isValidCoordinatePair(null, 12.0)) // half set
        assertFalse(Events.isValidCoordinatePair(91.0, 12.0)) // lat out of range
        assertFalse(Events.isValidCoordinatePair(57.0, 181.0)) // lng out of range
    }

    @Test
    fun `location picker starts on the existing pin, else the Kungsbacka default`() {
        assertEquals(
            57.5 to 12.1,
            EventLocationPicker.startCenter(latitude = 57.5, longitude = 12.1),
        )
        // No pin → default.
        assertEquals(
            EventLocationPicker.DEFAULT_LATITUDE to EventLocationPicker.DEFAULT_LONGITUDE,
            EventLocationPicker.startCenter(latitude = null, longitude = null),
        )
        // A half-set/invalid pair is treated as no pin, not a bogus centre.
        assertEquals(
            EventLocationPicker.DEFAULT_LATITUDE to EventLocationPicker.DEFAULT_LONGITUDE,
            EventLocationPicker.startCenter(latitude = 57.5, longitude = null),
        )
    }

    // ---- Create payload carries a complete pin ------------------------------

    @Test
    fun `createPayload includes both coordinates when a pin is set`() {
        val payload =
            Events.createPayload(createInput().copy(latitude = 57.4874, longitude = 12.0757))
        assertEquals(57.4874, payload["latitude"])
        assertEquals(12.0757, payload["longitude"])
    }

    @Test
    fun `createPayload omits coordinates when there is no pin`() {
        val payload = Events.createPayload(createInput())
        assertFalse(payload.containsKey("latitude"))
        assertFalse(payload.containsKey("longitude"))
    }

    // ---- Map pin filtering (event -> marker) --------------------------------

    @Test
    fun `mapPinEvents keeps only published, positioned, not-past events`() {
        val now = 10_000L
        val kept =
            Events.mapPinEvents(
                listOf(
                    // upcoming, positioned, published → kept
                    positioned("keep-upcoming", now + 1_000L, EventStatus.PUBLISHED),
                    // ongoing (ends in the future), published → kept
                    positioned("keep-ongoing", now - 1_000L, EventStatus.PUBLISHED, endsAtMillis = now + 500L),
                    // published but no coordinates → dropped
                    event("no-coords", now + 1_000L),
                    // draft → dropped (never public)
                    positioned("draft", now + 1_000L, EventStatus.DRAFT),
                    // cancelled → dropped (must never pin)
                    positioned("cancelled", now + 1_000L, EventStatus.CANCELLED),
                    // completed → dropped
                    positioned("completed", now + 1_000L, EventStatus.COMPLETED),
                    // past (ended before now) → dropped
                    positioned("past", now - 5_000L, EventStatus.PUBLISHED, endsAtMillis = now - 1_000L),
                ),
                now,
            )
        assertEquals(listOf("keep-upcoming", "keep-ongoing"), kept.map { it.id })
    }

    // ---- Pin expiry scheduling (the map re-filters on a clock) ---------------

    @Test
    fun `nextPinExpiryMillis is the soonest pinned event's effective end`() {
        val now = 10_000L
        val next =
            Events.nextPinExpiryMillis(
                listOf(
                    positioned("later", now + 9_000L, EventStatus.PUBLISHED),
                    // Ends soonest of the pinned ones → this is the wake-up.
                    positioned("soonest", now - 1_000L, EventStatus.PUBLISHED, endsAtMillis = now + 500L),
                    // Already past: not pinned, so it must not schedule anything.
                    positioned("past", now - 5_000L, EventStatus.PUBLISHED, endsAtMillis = now - 1_000L),
                    // Not pinned (cancelled) even though it ends sooner than "soonest".
                    positioned("cancelled", now + 100L, EventStatus.CANCELLED),
                ),
                now,
            )
        assertEquals(now + 500L, next)
    }

    @Test
    fun `nextPinExpiryMillis is null when nothing pinned is time-limited`() {
        val now = 10_000L
        // No pins at all.
        assertNull(Events.nextPinExpiryMillis(emptyList(), now))
        // Only an untimed pin: it never expires, so there is nothing to schedule.
        assertNull(
            Events.nextPinExpiryMillis(
                listOf(positioned("no-time", startsAtMillis = null, status = EventStatus.PUBLISHED)),
                now,
            ),
        )
        // Only a non-pinned (draft) event: also nothing to schedule.
        assertNull(
            Events.nextPinExpiryMillis(
                listOf(positioned("draft", now + 1_000L, EventStatus.DRAFT)),
                now,
            ),
        )
    }

    private fun event(id: String, startsAtMillis: Long?) =
        EventSummary(
            id = id,
            title = "Event $id",
            summary = null,
            startsAtMillis = startsAtMillis,
            endsAtMillis = null,
            approximateArea = "Kungsbacka",
            isOfficial = false,
            status = EventStatus.PUBLISHED,
            counts = RsvpCounts.EMPTY,
        )

    private fun positioned(
        id: String,
        startsAtMillis: Long?,
        status: EventStatus,
        endsAtMillis: Long? = null,
    ) =
        EventSummary(
            id = id,
            title = "Event $id",
            summary = null,
            startsAtMillis = startsAtMillis,
            endsAtMillis = endsAtMillis,
            approximateArea = "Kungsbacka",
            locationName = "Torg",
            latitude = 57.4874,
            longitude = 12.0757,
            isOfficial = false,
            status = status,
            counts = RsvpCounts.EMPTY,
        )
}
