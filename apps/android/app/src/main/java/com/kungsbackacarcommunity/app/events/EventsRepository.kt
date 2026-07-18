package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the published-events list. */
sealed interface EventsListState {
    data object Loading : EventsListState

    data object Error : EventsListState

    data class Loaded(val events: List<EventSummary>) : EventsListState
}

/**
 * Events read + RSVP operations (Phase 12 slice 9). Firebase-free interface so
 * the route/screens are unit- and UI-testable with fakes.
 *
 * Reads are direct Firestore snapshot listeners (published events are readable
 * by any authenticated user; member-gated details/RSVP are enforced by the
 * Security Rules). Writing an RSVP is a direct owner write of exactly
 * `{ status, updatedAt }`; the events-onRsvpWrite trigger maintains the counts.
 */
interface EventsRepository {
    /** Published events, soonest first; Loading until the first snapshot. */
    fun observePublishedEvents(): Flow<EventsListState>

    /**
     * Past events — status `completed`, most recent first; Loading until the
     * first snapshot. An event reaches `completed` either from the hourly
     * `events-autoClose` sweep (effective end + 6h) or from its creator/an
     * admin calling `events.complete`; both are terminal and mean the same
     * thing, so one query covers the whole archive. Teasers of completed
     * events are readable by any authenticated user; their member-gated
     * details, chat and group-drive roster stay closed.
     */
    fun observePastEvents(): Flow<EventsListState>

    /** A single event's teaser doc; null when missing/unreadable. */
    fun observeEvent(eventId: String): Flow<EventSummary?>

    /** Member-gated detail; emits null when denied (non-member) or missing. */
    fun observeEventDetail(eventId: String): Flow<EventDetail?>

    /** The caller's own RSVP answer; null when they have not responded. */
    fun observeMyRsvp(eventId: String, uid: String): Flow<RsvpStatus?>

    /** Writes/updates the caller's RSVP answer (rules-validated). */
    suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus)

    /**
     * Creates a new event via the `events-create` callable and returns the new
     * event id. An active member may call this: their event publishes
     * immediately and is moderated afterwards. Throws [CreateEventException]
     * with [CreateEventFailure.RATE_LIMITED] when the member's
     * 3-per-rolling-24h cap is hit, and [CreateEventFailure.UNKNOWN] otherwise.
     */
    suspend fun createEvent(input: CreateEventInput): String

    /**
     * One-shot read of who is going to [eventId]. Deliberately not a Flow:
     * under the current rules this is denied for a normal member (see
     * [EventAttendees]), so a permanently-erroring snapshot listener would buy
     * nothing — the section offers an explicit retry instead. Never throws;
     * failures are modelled as [EventAttendeesResult.Unavailable] /
     * [EventAttendeesResult.Unknown].
     */
    suspend fun loadAttendees(eventId: String): EventAttendeesResult
}
