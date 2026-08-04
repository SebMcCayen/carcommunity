package com.kungsbackacarcommunity.app.events

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [EventsRepository] backed by Cloud Firestore (Phase 12 slice 9).
 *
 * Published events are read with an equality filter (status == published)
 * ordered soonest-start-first and bounded to
 * [Events.PUBLISHED_EVENTS_QUERY_LIMIT] — the same soonest-first order the
 * list displays ([Events.sortedForList]), so capping the query keeps exactly
 * the events the screen would show first as the collection grows without
 * bound over the app's lifetime. Uses the existing `events` composite index
 * (status ASC, startsAt ASC — firebase/firestore.indexes.json), so no new
 * index is required.
 *
 * The past/archive list ([observePastEvents]) is the same shape with
 * `status == completed` and the order reversed (most recent first), and it
 * DOES need its own index: `events (status ASC, startsAt DESC)`.
 *
 * An earlier version of this comment claimed the opposite — that because a
 * composite index is traversable in both directions and `status` is pinned by
 * an equality filter, `(status ASC, startsAt ASC)` also serves this query.
 * That is wrong, and it is why the Past tab showed a permanent error while
 * Upcoming worked. Firestore matches an index whose field ordering equals the
 * query's ordering or is its EXACT full reverse. This query's ordering is
 * `(status ASC, startsAt DESC)`; the declared index is
 * `(status ASC, startsAt ASC)`, whose only other usable reading is
 * `(status DESC, startsAt DESC)`. Neither matches, so the listener fails with
 * `FAILED_PRECONDITION`. Every other equality-plus-descending query in this
 * repo (moderationReports, offers, announcements, crownHuntPoints, …)
 * declares its DESCENDING entry explicitly; this query was the sole exception.
 *
 * `firestore.indexes.json` is a HAND-DEPLOY — no workflow ships it — so adding
 * the entry does not fix a running app until someone runs
 * `firebase deploy --only firestore:indexes`.
 *
 * Both deploy-gated failure modes surface as [EventsListState.Error] carrying
 * the Firestore status name, never as a silently empty list:
 * `FAILED_PRECONDITION` (index not deployed) and `PERMISSION_DENIED` (the
 * `completed`-teaser read rule not deployed). [EventsErrorReporting] auto-files
 * exactly those two and stays quiet for the offline codes.
 *
 * Member-gated details and RSVP writes rely on the
 * Security Rules; an RSVP is a direct owner write of exactly
 * `{ status, updatedAt }`. Construction is guarded ([createIfAvailable]
 * returns null without Firebase).
 */
class FirebaseEventsRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : EventsRepository {

    override fun observePublishedEvents(): Flow<EventsListState> = callbackFlow {
        val registration =
            firestore
                .collection(EVENTS)
                .whereEqualTo("status", EventStatus.PUBLISHED.wire)
                .orderBy(STARTS_AT, Query.Direction.ASCENDING)
                .limit(Events.PUBLISHED_EVENTS_QUERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // Tag with the Firestore status name only (never the
                        // exception text) so the route can auto-file structural
                        // faults and ignore "no signal".
                        trySend(EventsListState.Error(error.firestoreCode()))
                        return@addSnapshotListener
                    }
                    val events = snapshot?.documents?.mapNotNull { it.toEventSummary() } ?: emptyList()
                    trySend(EventsListState.Loaded(Events.sortedForList(events)))
                }
        awaitClose { registration.remove() }
    }

    override fun observePastEvents(): Flow<EventsListState> = callbackFlow {
        val registration =
            firestore
                .collection(EVENTS)
                .whereEqualTo("status", EventStatus.COMPLETED.wire)
                .orderBy(STARTS_AT, Query.Direction.DESCENDING)
                .limit(Events.PAST_EVENTS_QUERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(EventsListState.Error(error.firestoreCode()))
                        return@addSnapshotListener
                    }
                    val events = snapshot?.documents?.mapNotNull { it.toEventSummary() } ?: emptyList()
                    trySend(EventsListState.Loaded(Events.sortedForPastList(events)))
                }
        awaitClose { registration.remove() }
    }

    override fun observeEvent(eventId: String): Flow<EventSummary?> = callbackFlow {
        val registration =
            firestore.collection(EVENTS).document(eventId).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toEventSummary())
            }
        awaitClose { registration.remove() }
    }

    override fun observeEventDetail(eventId: String): Flow<EventDetail?> = callbackFlow {
        val registration =
            firestore
                .collection(EVENTS)
                .document(eventId)
                .collection(DETAILS)
                .document(PRIVATE)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // Non-members are denied this read — surface null, not an error.
                        trySend(null)
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.toEventDetail())
                }
        awaitClose { registration.remove() }
    }

    override fun observeMyRsvp(eventId: String, uid: String): Flow<RsvpStatus?> = callbackFlow {
        val registration =
            firestore
                .collection(EVENTS)
                .document(eventId)
                .collection(RSVPS)
                .document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    trySend(RsvpStatus.fromWire(snapshot?.getString("status")))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus) {
        val doc =
            mapOf(
                "status" to status.wire,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        suspendCancellableCoroutine { continuation ->
            firestore
                .collection(EVENTS)
                .document(eventId)
                .collection(RSVPS)
                .document(uid)
                .set(doc)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("RSVP write failed without a cause"),
                        )
                    }
                }
        }
    }

    override suspend fun createEvent(input: CreateEventInput): String =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(CREATE)
                .call(Events.createPayload(input))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        val data = task.result?.data as? Map<String, Any?>
                        val eventId = data?.get("eventId") as? String
                        if (eventId != null) {
                            continuation.resume(eventId)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException("events-create returned no eventId"),
                            )
                        }
                    } else {
                        val cause = task.exception
                        // The per-member 3-per-24h cap answers `resource-exhausted`.
                        // Mapped to a domain reason here so the coordinator and the
                        // form stay Firebase-free and can say something true, rather
                        // than "please try again" — the one piece of advice that is
                        // guaranteed not to work for a rate limit.
                        val code = (cause as? FirebaseFunctionsException)?.code?.name
                        continuation.resumeWithException(
                            CreateEventException(Events.createFailureFromCode(code), cause),
                        )
                    }
                }
        }

    override suspend fun loadAttendees(eventId: String): EventAttendeesResult {
        // Roster read via the `events-listAttendees` callable: the raw RSVP doc
        // is owner-or-admin readable and carries only { status, updatedAt }, so
        // the identity join (users/{uid} → displayName + avatarPath) and the
        // block filtering are done server-side and returned in ONE call, grouped
        // by RSVP answer. See functions/src/events/listAttendees.ts.
        val response =
            runCatchingCancellable {
                functions
                    .getHttpsCallable(LIST_ATTENDEES)
                    .call(mapOf("eventId" to eventId))
                    .awaitResult()
            }
                .getOrElse { error ->
                    // NOT_FOUND = draft/cancelled/completed or unknown event;
                    // PERMISSION_DENIED/UNAUTHENTICATED = restricted caller —
                    // all definitive "not available to you", never a fabricated
                    // list. Anything else (offline, timeout) stays retryable.
                    return when ((error as? FirebaseFunctionsException)?.code) {
                        FirebaseFunctionsException.Code.NOT_FOUND,
                        FirebaseFunctionsException.Code.PERMISSION_DENIED,
                        FirebaseFunctionsException.Code.UNAUTHENTICATED,
                        -> EventAttendeesResult.Unavailable

                        else -> EventAttendeesResult.Unknown
                    }
                }

        // A missing/malformed payload (non-map, or absent/non-list `attendees`)
        // is a backend/serialization failure, NOT an empty roster — it folds to
        // the retryable Unknown state rather than a fabricated "nobody answered".
        // Only a present, well-formed (possibly empty) list loads. See
        // EventAttendees.parseAttendeesPayload.
        return EventAttendees.parseAttendeesPayload(response.data)
    }

    companion object {
        private const val EVENTS = "events"
        private const val STARTS_AT = "startsAt"
        private const val DETAILS = "details"
        private const val PRIVATE = "private"
        private const val RSVPS = "rsvps"
        private const val REGION = "europe-west1"
        private const val CREATE = "events-create"
        private const val LIST_ATTENDEES = "events-listAttendees"

        fun createIfAvailable(context: Context): EventsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseEventsRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

/**
 * The bare Firestore status name (`FAILED_PRECONDITION`, `PERMISSION_DENIED`,
 * `UNAVAILABLE`, …) for a listener error, or null when the failure is not a
 * [FirebaseFirestoreException].
 *
 * Deliberately drops everything else. The value reaches a PUBLIC GitHub issue
 * via the error-reporting pipeline, and `Exception.message` from Firestore
 * embeds the failing query — including the index-creation URL, which carries
 * the project id. A status name is the whole diagnosis and leaks nothing.
 */
private fun Exception.firestoreCode(): String? =
    (this as? FirebaseFirestoreException)?.code?.name

/** Minimal Task -> suspend bridge (no kotlinx-coroutines-play-services dep). */
private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }.addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

private fun DocumentSnapshot.toEventSummary(): EventSummary? {
    if (!exists()) return null
    val title = getString("title") ?: return null
    val status = EventStatus.fromWire(getString("status")) ?: return null
    @Suppress("UNCHECKED_CAST")
    val counts = RsvpCounts.fromMap(get("rsvpCounts") as? Map<String, Any?>)
    return EventSummary(
        id = id,
        title = title,
        summary = getString("summary"),
        startsAtMillis = getTimestamp("startsAt")?.toDate()?.time,
        endsAtMillis = getTimestamp("endsAt")?.toDate()?.time,
        approximateArea = getString("approximateArea"),
        // Public map location (2026-07): read off the teaser so the map can pin
        // every published event without the member gate.
        locationName = getString("locationName"),
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        isOfficial = getBoolean("isOfficial") ?: false,
        status = status,
        counts = counts,
    )
}

private fun DocumentSnapshot.toEventDetail(): EventDetail? {
    if (!exists()) return null
    // Member-gated fields only; the map location lives on the teaser now.
    return EventDetail(
        description = getString("description"),
        address = getString("address"),
    )
}
