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
    fun `canRsvp requires an active member on a published event`() {
        assertTrue(Events.canRsvp(isActiveMember = true, status = EventStatus.PUBLISHED))
        assertFalse(Events.canRsvp(isActiveMember = false, status = EventStatus.PUBLISHED))
        assertFalse(Events.canRsvp(isActiveMember = true, status = EventStatus.CANCELLED))
        assertFalse(Events.canRsvp(isActiveMember = true, status = EventStatus.COMPLETED))
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

    private fun createInput() =
        CreateEventInput(
            title = "Cars & Coffee",
            approximateArea = "Kungsbacka",
            startsAtMillis = 1_783_794_600_000L,
        )

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
}
