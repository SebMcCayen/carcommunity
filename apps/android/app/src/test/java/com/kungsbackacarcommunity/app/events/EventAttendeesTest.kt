package com.kungsbackacarcommunity.app.events

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure coverage for the attendee state model and the create-event failure
 * mapping — the two pieces of logic that decide whether the UI tells the member
 * the truth about who is going and about the 3-per-24h creation cap.
 */
class EventAttendeesTest {

    private fun attendee(uid: String, name: String? = null, status: RsvpStatus = RsvpStatus.GOING) =
        EventAttendee(uid = uid, displayName = name, avatarPath = null, status = status)

    // -- create failure mapping ------------------------------------------------

    @Test
    fun `resource-exhausted maps to RATE_LIMITED in both SDK and wire spellings`() {
        // The Firebase Android SDK reports the enum name; HttpsError uses the
        // hyphenated wire code. Both must land on the rate-limit message.
        assertEquals(CreateEventFailure.RATE_LIMITED, Events.createFailureFromCode("RESOURCE_EXHAUSTED"))
        assertEquals(CreateEventFailure.RATE_LIMITED, Events.createFailureFromCode("resource-exhausted"))
        assertEquals(CreateEventFailure.RATE_LIMITED, Events.createFailureFromCode("Resource_Exhausted"))
    }

    @Test
    fun `other codes and a missing code map to UNKNOWN`() {
        assertEquals(CreateEventFailure.UNKNOWN, Events.createFailureFromCode("PERMISSION_DENIED"))
        assertEquals(CreateEventFailure.UNKNOWN, Events.createFailureFromCode("INVALID_ARGUMENT"))
        assertEquals(CreateEventFailure.UNKNOWN, Events.createFailureFromCode("UNAVAILABLE"))
        assertEquals(CreateEventFailure.UNKNOWN, Events.createFailureFromCode(""))
        assertEquals(CreateEventFailure.UNKNOWN, Events.createFailureFromCode(null))
    }

    @Test
    fun `the advertised cap matches the backend MEMBER_EVENT_RATE_LIMIT_MAX`() {
        // functions/src/events/events-core.ts: MEMBER_EVENT_RATE_LIMIT_MAX = 3.
        // The message quotes this number, so a backend change must break here.
        assertEquals(3, Events.MEMBER_EVENT_RATE_LIMIT_PER_DAY)
    }

    // -- callable payload parsing ----------------------------------------------

    @Test
    fun `a well-formed attendees payload loads, dropping malformed rows`() {
        val payload =
            mapOf(
                "attendees" to
                    listOf(
                        mapOf("userId" to "u1", "displayName" to "Alice", "avatarPath" to "a/u1.jpg", "status" to "going"),
                        mapOf("userId" to "u2", "displayName" to null, "avatarPath" to null, "status" to "maybe"),
                        // Malformed rows: no uid, non-canonical status, wrong type — all dropped.
                        mapOf("displayName" to "No Uid", "status" to "going"),
                        mapOf("userId" to "u3", "status" to "definitely"),
                        "not-a-row",
                    ),
            )
        val result = EventAttendees.parseAttendeesPayload(payload)
        assertEquals(
            EventAttendeesResult.Loaded(
                listOf(
                    EventAttendee("u1", "Alice", "a/u1.jpg", RsvpStatus.GOING),
                    attendee("u2", null, RsvpStatus.MAYBE),
                ),
            ),
            result,
        )
    }

    @Test
    fun `a present empty attendees array is an empty roster, not an error`() {
        // The read succeeded and nobody has answered — Loaded(empty), which the
        // state model then folds to Empty. This is the ONLY empty-roster path.
        assertEquals(
            EventAttendeesResult.Loaded(emptyList()),
            EventAttendees.parseAttendeesPayload(mapOf("attendees" to emptyList<Any?>())),
        )
    }

    @Test
    fun `a missing or malformed payload is Unknown, never a fabricated empty roster`() {
        // A backend/serialization bug must surface as a retryable error, not as
        // "nobody answered" — otherwise a broken read silently hides the roster.
        assertEquals(EventAttendeesResult.Unknown, EventAttendees.parseAttendeesPayload(null))
        assertEquals(EventAttendeesResult.Unknown, EventAttendees.parseAttendeesPayload("unexpected-string"))
        assertEquals(EventAttendeesResult.Unknown, EventAttendees.parseAttendeesPayload(emptyMap<String, Any?>()))
        assertEquals(
            EventAttendeesResult.Unknown,
            EventAttendees.parseAttendeesPayload(mapOf("other" to 1)),
        )
        assertEquals(
            EventAttendeesResult.Unknown,
            EventAttendees.parseAttendeesPayload(mapOf("attendees" to "not-a-list")),
        )
    }

    // -- attendee state model --------------------------------------------------

    @Test
    fun `a populated roster loads`() {
        val result = EventAttendeesResult.Loaded(listOf(attendee("u1", "Alice")))
        assertEquals(
            EventAttendeesState.Loaded(listOf(attendee("u1", "Alice"))),
            EventAttendees.stateFor(result),
        )
    }

    @Test
    fun `an empty roster is Empty, not Unavailable`() {
        // "Nobody is going yet" and "you may not see who is going" are different
        // facts; conflating them would misreport a readable, empty list.
        assertEquals(
            EventAttendeesState.Empty,
            EventAttendees.stateFor(EventAttendeesResult.Loaded(emptyList())),
        )
    }

    @Test
    fun `a denied roster read is Unavailable`() {
        // The honest state for a normal member under the current owner-or-admin
        // rule on events/{id}/rsvps/{uid}.
        assertEquals(
            EventAttendeesState.Unavailable,
            EventAttendees.stateFor(EventAttendeesResult.Unavailable),
        )
    }

    @Test
    fun `a transient failure is Error, not the definitive Unavailable`() {
        assertEquals(
            EventAttendeesState.Error,
            EventAttendees.stateFor(EventAttendeesResult.Unknown),
        )
    }

    @Test
    fun `blocked uids are irrelevant unless the roster loaded`() {
        // EventsRoute relies on this to skip the block-list read entirely on the
        // non-Loaded branches (Unavailable is the common path for a member, and
        // waiting on a read whose result cannot change the state would just hold
        // the section on Loading). If a future change makes the block list matter
        // here, that skip becomes wrong — and this test is what says so.
        val blocked = setOf("u1", "u2")
        assertEquals(
            EventAttendees.stateFor(EventAttendeesResult.Unavailable),
            EventAttendees.stateFor(EventAttendeesResult.Unavailable, blocked),
        )
        assertEquals(
            EventAttendees.stateFor(EventAttendeesResult.Unknown),
            EventAttendees.stateFor(EventAttendeesResult.Unknown, blocked),
        )
    }

    @Test
    fun `blocked members are dropped from the roster`() {
        val result =
            EventAttendeesResult.Loaded(
                listOf(attendee("u1", "Alice"), attendee("blocked", "Mallory")),
            )
        val state = EventAttendees.stateFor(result, blockedUids = setOf("blocked"))
        assertEquals(EventAttendeesState.Loaded(listOf(attendee("u1", "Alice"))), state)
    }

    @Test
    fun `an all-blocked roster folds to Empty rather than Unavailable`() {
        // The read succeeded — there is simply nobody left to show. Reporting
        // "names aren't shown" here would blame the backend for the viewer's
        // own block list.
        val result = EventAttendeesResult.Loaded(listOf(attendee("blocked", "Mallory")))
        assertEquals(
            EventAttendeesState.Empty,
            EventAttendees.stateFor(result, blockedUids = setOf("blocked")),
        )
    }

    @Test
    fun `named members sort alphabetically ahead of nameless ones`() {
        val sorted =
            EventAttendees.sortedForDisplay(
                listOf(
                    attendee("u3", null),
                    attendee("u2", "bob"),
                    attendee("u1", "Alice"),
                    attendee("u4", "  "),
                ),
            )
        assertEquals(listOf("u1", "u2", "u3", "u4"), sorted.map { it.uid })
    }

    @Test
    fun `sorting is stable for duplicate names via the uid tiebreaker`() {
        val sorted =
            EventAttendees.sortedForDisplay(
                listOf(attendee("u2", "Alice"), attendee("u1", "Alice")),
            )
        assertEquals(listOf("u1", "u2"), sorted.map { it.uid })
    }

    // -- status grouping -------------------------------------------------------

    @Test
    fun `groupedByStatus orders going then maybe then not_going`() {
        val groups =
            EventAttendees.groupedByStatus(
                listOf(
                    attendee("u3", "C", RsvpStatus.NOT_GOING),
                    attendee("u1", "A", RsvpStatus.GOING),
                    attendee("u2", "B", RsvpStatus.MAYBE),
                ),
            )
        assertEquals(
            listOf(RsvpStatus.GOING, RsvpStatus.MAYBE, RsvpStatus.NOT_GOING),
            groups.map { it.status },
        )
        assertEquals(listOf("u1"), groups[0].members.map { it.uid })
        assertEquals(listOf("u2"), groups[1].members.map { it.uid })
        assertEquals(listOf("u3"), groups[2].members.map { it.uid })
    }

    @Test
    fun `groupedByStatus omits empty groups`() {
        // Nobody answered "maybe" — no Kanske header should be produced.
        val groups =
            EventAttendees.groupedByStatus(
                listOf(
                    attendee("u1", "A", RsvpStatus.GOING),
                    attendee("u2", "B", RsvpStatus.NOT_GOING),
                ),
            )
        assertEquals(listOf(RsvpStatus.GOING, RsvpStatus.NOT_GOING), groups.map { it.status })
    }

    @Test
    fun `groupedByStatus sorts members within a group by name`() {
        val groups =
            EventAttendees.groupedByStatus(
                listOf(
                    attendee("u2", "Bob", RsvpStatus.GOING),
                    attendee("u1", "Alice", RsvpStatus.GOING),
                    attendee("u3", null, RsvpStatus.GOING),
                ),
            )
        assertEquals(1, groups.size)
        assertEquals(listOf("u1", "u2", "u3"), groups[0].members.map { it.uid })
    }

    @Test
    fun `groupedByStatus on an empty roster yields no groups`() {
        assertEquals(emptyList<EventAttendeeGroup>(), EventAttendees.groupedByStatus(emptyList()))
    }
}
