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
     * event id. NOTE: that callable is currently admin-only (requireAdminActor),
     * so a non-admin caller gets a permission-denied failure — enabling
     * user-created events needs a backend/rules change (out of the Android lane).
     */
    suspend fun createEvent(input: CreateEventInput): String
}
