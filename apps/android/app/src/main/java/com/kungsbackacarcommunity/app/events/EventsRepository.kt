package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the published-events list. */
sealed interface EventsListState {
    data object Loading : EventsListState

    /**
     * The listener failed. [code] is the Firestore status name when one was
     * available (e.g. `FAILED_PRECONDITION` for a missing composite index,
     * `PERMISSION_DENIED` for a rules denial, `UNAVAILABLE` when offline) —
     * carried so the route can tell a STRUCTURAL fault apart from "this phone
     * has no signal" and auto-file only the former. A bare status name, never
     * exception text: see [EventsErrorReporting].
     */
    data class Error(val code: String? = null) : EventsListState

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
     * thing, so one query covers the whole archive.
     *
     * Two DEPLOY-GATED preconditions, both of which surface as
     * [EventsListState.Error] rather than an empty list when unmet:
     *  - the `firestore.rules` clause letting a non-admin read a `completed`
     *    teaser (present on main) — otherwise `PERMISSION_DENIED`;
     *  - the `events` (status ASC, startsAt **DESC**) composite index —
     *    otherwise `FAILED_PRECONDITION`. The ascending index does NOT cover
     *    this query; see [FirebaseEventsRepository].
     *
     * Member-gated details, chat and the group-drive roster stay
     * `published`-gated either way: an ended event can be looked up, not
     * re-entered.
     */
    fun observePastEvents(): Flow<EventsListState>

    /** A single event's teaser doc; null when missing/unreadable. */
    fun observeEvent(eventId: String): Flow<EventSummary?>

    /** Member-gated detail; emits null when denied (non-member) or missing. */
    fun observeEventDetail(eventId: String): Flow<EventDetail?>

    /** The caller's own RSVP answer; null when they have not responded. */
    fun observeMyRsvp(eventId: String, uid: String): Flow<RsvpStatus?>

    /**
     * The caller's own attendance record for [eventId] (eventAttendance is
     * owner-readable), or null when they have never checked in. Lets the detail
     * screen show a confirmed/pending state that survives a restart, rather than
     * only reflecting the current session's check-in.
     */
    fun observeMyAttendance(eventId: String, uid: String): Flow<EventAttendanceStatus?>

    /**
     * Submits ONE geofenced check-in sample via the `events-checkIn` callable
     * and returns the server's [CheckInResult]. The client sends only where and
     * when it was (plus the mock flag); the server decides geofence, dwell and
     * anti-fraud. Never returns a fabricated success — a garbled response maps to
     * [CheckInResult.UNKNOWN], and a transport failure propagates to the caller.
     */
    suspend fun checkIn(eventId: String, fix: CheckInFix): CheckInResult

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
     * Applies a creator's partial edit to [eventId] via the `events-update`
     * callable (the fields the app's event form manages — see
     * [Events.updatePayload]). Throws [UpdateEventException] carrying a
     * [ManageEventFailure]: [ManageEventFailure.PERMISSION_DENIED] when the caller
     * is not the creator, [ManageEventFailure.IMMUTABLE] when the event is already
     * cancelled/completed, [ManageEventFailure.UNKNOWN] otherwise.
     */
    suspend fun updateEvent(eventId: String, input: CreateEventInput)

    /**
     * Removes (cancels) [eventId] via the `events-cancel` callable with the given
     * audit [reason]; the backend sets `cancelledAt` and never hard-deletes, so a
     * cancelled event simply drops out of the published list. Throws
     * [CancelEventException] carrying a [ManageEventFailure] with the same
     * den/immutable/unknown vocabulary as [updateEvent].
     */
    suspend fun cancelEvent(eventId: String, reason: String)

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
