package com.kungsbackacarcommunity.app.events

/**
 * Event attendee list ("who's going") — model + pure logic.
 *
 * BACKEND REALITY (verified against firebase/firestore.rules, not assumed):
 * `events/{eventId}/rsvps/{userId}` is `allow read: if isOwner(userId) ||
 * isAdmin()` — a member may read ONLY their own RSVP document, and no
 * callable exposes an attendee list (functions/src/index.ts events domain =
 * create/update/publish/cancel/complete + the onRsvpWrite counter trigger).
 * So for a normal member the roster genuinely is not readable, and the honest
 * UI state is [EventAttendeesState.Unavailable] — never a fabricated list.
 *
 * What IS readable by everyone is the denormalized tally
 * `events/{eventId}.rsvpCounts` maintained by `events-onRsvpWrite`, so the
 * COUNT is always shown even when the names are not. That split — public
 * number, private names — is the whole design here.
 *
 * The repository still attempts the read rather than hardcoding "denied", and
 * maps PERMISSION_DENIED onto [Unavailable] (mirroring the identical
 * `MemberBadges.Unavailable` treatment of owner-only badges). Two reasons:
 * an admin signed into the app gets the real roster today, and if the backend
 * lane later opens the read up, this UI lights up with no Android change.
 */

/** One attendee: the RSVP uid joined with their public users/{uid} identity. */
data class EventAttendee(
    val uid: String,
    /** Null when users/{uid} has no display name — rendered as a neutral fallback. */
    val displayName: String? = null,
    /** Cloud Storage path of the avatar; a URL is resolved lazily for rendering. */
    val avatarPath: String? = null,
)

/** The repository's one-shot attendee read outcome. Blocking is applied a layer up. */
sealed interface EventAttendeesResult {
    data class Loaded(val attendees: List<EventAttendee>) : EventAttendeesResult

    /**
     * Genuinely not visible to this viewer: the roster read was denied
     * (PERMISSION_DENIED) under the current owner-or-admin rule. A definitive
     * "names aren't shown", not a failure.
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

    /** Read succeeded and at least one member is going. */
    data class Loaded(val attendees: List<EventAttendee>) : EventAttendeesState

    /** Read succeeded and nobody is going (yet). */
    data object Empty : EventAttendeesState

    /** Names are not readable by this viewer (see the file header). */
    data object Unavailable : EventAttendeesState

    /** Transient failure — offer a retry rather than an explanation. */
    data object Error : EventAttendeesState
}

object EventAttendees {
    /** Upper bound on attendee identities joined for one event, so a huge meet
     * cannot fan out into an unbounded number of users/{uid} reads. */
    const val MAX_RENDERED = 50

    /**
     * Folds a repository [result] into the UI state, dropping anyone the viewer
     * has blocked ([blockedUids]) — blocked members are invisible to the viewer
     * everywhere else (chat authors, member profiles), and an attendee list is
     * no different. Note the COUNT shown alongside comes from the server's
     * rsvpCounts and is deliberately NOT adjusted: it is a public tally, and
     * silently decrementing it would leak who the viewer blocked.
     *
     * An all-blocked roster folds to [EventAttendeesState.Empty], not
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
