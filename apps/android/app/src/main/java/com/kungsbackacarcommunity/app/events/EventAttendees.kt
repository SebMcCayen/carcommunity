package com.kungsbackacarcommunity.app.events

/**
 * Event attendee list ("who answered") — model + pure logic.
 *
 * BACKEND (verified against functions/src/events/listAttendees.ts, not assumed):
 * `events/{eventId}/rsvps/{userId}` stays owner-or-admin readable and carries
 * only { status, updatedAt }, so the roster is fetched through the member-gated
 * `events-listAttendees` callable, which joins each RSVP with the public
 * users/{uid} identity and drops blocked members SERVER-side, returning
 * `{ attendees: [{ userId, displayName, avatarPath, status }] }`. A normal
 * member now sees WHO answered and WHICH answer they gave.
 *
 * The callable exposes only PUBLISHED events; a draft/cancelled/completed or
 * unknown event answers NOT_FOUND, which the repository folds to
 * [EventAttendeesState.Unavailable] — an honest "names aren't shown", never a
 * fabricated list. The public `events/{eventId}.rsvpCounts` tally is unchanged
 * and still drives the count shown in the section header.
 */

/** One attendee: the RSVP uid joined with their public users/{uid} identity. */
data class EventAttendee(
    val uid: String,
    /** Null when users/{uid} has no display name — rendered as a neutral fallback. */
    val displayName: String? = null,
    /** Cloud Storage path of the avatar; a URL is resolved lazily for rendering. */
    val avatarPath: String? = null,
    /** The answer this member gave (going / maybe / not_going). */
    val status: RsvpStatus = RsvpStatus.GOING,
)

/** A status group as rendered in the UI: an answer + the members who gave it. */
data class EventAttendeeGroup(
    val status: RsvpStatus,
    val members: List<EventAttendee>,
)

/** The repository's one-shot attendee read outcome. Blocking is applied a layer up. */
sealed interface EventAttendeesResult {
    data class Loaded(val attendees: List<EventAttendee>) : EventAttendeesResult

    /**
     * Genuinely not visible to this viewer: the callable answered NOT_FOUND
     * (a draft/cancelled/completed or unknown event exposes no roster) or
     * PERMISSION_DENIED (a restricted caller). A definitive "names aren't
     * shown", not a failure.
     */
    data object Unavailable : EventAttendeesResult

    /**
     * The read failed for a transient/unknown reason (offline, timeout,
     * misconfig) — NOT a permission denial, so it must not be misreported as
     * the definitive [Unavailable] explanation.
     */
    data object Unknown : EventAttendeesResult
}

/** UI-facing state of the attendee section. */
sealed interface EventAttendeesState {
    data object Loading : EventAttendeesState

    /** Read succeeded and at least one member answered. Grouped by status in the UI. */
    data class Loaded(val attendees: List<EventAttendee>) : EventAttendeesState

    /** Read succeeded and nobody has answered (yet). */
    data object Empty : EventAttendeesState

    /** Names are not readable by this viewer (see the file header). */
    data object Unavailable : EventAttendeesState

    /** Transient failure — offer a retry rather than an explanation. */
    data object Error : EventAttendeesState
}

object EventAttendees {
    /** Status render order for the groups: going, then maybe, then not_going. */
    private val STATUS_ORDER = listOf(RsvpStatus.GOING, RsvpStatus.MAYBE, RsvpStatus.NOT_GOING)

    /**
     * Folds the raw `events-listAttendees` callable payload into a result,
     * distinguishing a genuinely-empty roster from a MISSING or MALFORMED
     * payload.
     *
     * A backend/serialization bug that yields a non-map payload, or a payload
     * whose `attendees` field is absent or not a list, must NOT masquerade as
     * "nobody answered" — that would silently hide who is going and give the
     * viewer no way to retry. Those shapes fold to [EventAttendeesResult.Unknown]
     * (the retryable error state; the UI offers "couldn't load, try again"),
     * mirroring how the non-permission callable failure is surfaced. Only a
     * PRESENT, well-formed `attendees` list maps to [EventAttendeesResult.Loaded]
     * — an empty list being the honest "nobody has answered yet".
     *
     * Individual malformed ROWS inside a well-formed list are still dropped
     * (missing uid / non-canonical status): the server only ever emits canonical
     * rows, so a bad row is belt-and-braces, not a reason to fail the whole read.
     *
     * `data` is typed [Any] so this stays Firebase-free and unit-testable; the
     * repository passes `HttpsCallableResult.data` straight through.
     */
    fun parseAttendeesPayload(data: Any?): EventAttendeesResult {
        val map = data as? Map<*, *> ?: return EventAttendeesResult.Unknown
        val rawAttendees = map["attendees"] as? List<*> ?: return EventAttendeesResult.Unknown
        val attendees =
            rawAttendees.mapNotNull { item ->
                val row = item as? Map<*, *> ?: return@mapNotNull null
                val uid = row["userId"] as? String ?: return@mapNotNull null
                val status = RsvpStatus.fromWire(row["status"] as? String) ?: return@mapNotNull null
                EventAttendee(
                    uid = uid,
                    displayName = row["displayName"] as? String,
                    avatarPath = row["avatarPath"] as? String,
                    status = status,
                )
            }
        return EventAttendeesResult.Loaded(attendees)
    }

    /**
     * Folds a repository [result] into the UI state, dropping anyone the viewer
     * has blocked ([blockedUids]). The `events-listAttendees` callable already
     * filters blocks server-side, so this is a defensive second pass — belt and
     * braces against a stale block-list gap, and cost-free on the common path
     * where the callable already excluded them. Note the COUNT shown alongside
     * comes from the server's rsvpCounts and is deliberately NOT adjusted: it is
     * a public tally, and silently decrementing it would leak who the viewer
     * blocked.
     *
     * An all-blocked (or empty) roster folds to [EventAttendeesState.Empty], not
     * [EventAttendeesState.Unavailable] — the read worked; there is simply
     * nobody left to show.
     */
    fun stateFor(
        result: EventAttendeesResult,
        blockedUids: Set<String> = emptySet(),
    ): EventAttendeesState =
        when (result) {
            is EventAttendeesResult.Unavailable -> EventAttendeesState.Unavailable
            is EventAttendeesResult.Unknown -> EventAttendeesState.Error
            is EventAttendeesResult.Loaded -> {
                val visible = sortedForDisplay(result.attendees.filter { it.uid !in blockedUids })
                if (visible.isEmpty()) EventAttendeesState.Empty else EventAttendeesState.Loaded(visible)
            }
        }

    /**
     * Partitions a loaded roster into the three status groups in render order
     * (going, maybe, not_going), each internally sorted by [sortedForDisplay].
     * Empty groups are omitted, so the UI only ever shows the answers people
     * actually gave (a meet with nobody on "maybe" shows no "Kanske" header).
     */
    fun groupedByStatus(attendees: List<EventAttendee>): List<EventAttendeeGroup> =
        STATUS_ORDER.mapNotNull { status ->
            val members = sortedForDisplay(attendees.filter { it.status == status })
            if (members.isEmpty()) null else EventAttendeeGroup(status, members)
        }

    /**
     * Named members first (alphabetically, case-insensitive), then the nameless
     * — so the list reads as people rather than as a shuffled bag of uids. The
     * uid is the tiebreaker, making the order stable across recompositions.
     */
    fun sortedForDisplay(attendees: List<EventAttendee>): List<EventAttendee> =
        attendees.sortedWith(
            compareBy<EventAttendee> { it.displayName.isNullOrBlank() }
                .thenBy { it.displayName?.lowercase() ?: "" }
                .thenBy { it.uid },
        )
}
