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
