package com.kungsbackacarcommunity.app.events

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [EventsRepository] backed by Cloud Firestore (Phase 12 slice 9).
 *
 * Published events are read with an equality filter only (status == published)
 * and sorted client-side ([Events.sortedForList]) so the query needs no
 * composite index. Member-gated details and RSVP writes rely on the Security
 * Rules; an RSVP is a direct owner write of exactly `{ status, updatedAt }`.
 * Construction is guarded ([createIfAvailable] returns null without Firebase).
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
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(EventsListState.Error)
                        return@addSnapshotListener
                    }
                    val events = snapshot?.documents?.mapNotNull { it.toEventSummary() } ?: emptyList()
                    trySend(EventsListState.Loaded(Events.sortedForList(events)))
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
        // Roster read. Under the current rules (owner-or-admin on
        // events/{id}/rsvps/{uid}) this list query is DENIED for a normal
        // member — an expected denial that collapses to the definitive
        // "names aren't shown" note. See EventAttendees.kt for why the read is
        // attempted rather than assumed-denied.
        val rsvps =
            runCatchingCancellable {
                firestore
                    .collection(EVENTS)
                    .document(eventId)
                    .collection(RSVPS)
                    .whereEqualTo("status", RsvpStatus.GOING.wire)
                    .limit(EventAttendees.MAX_RENDERED.toLong())
                    .get()
                    .awaitResult()
            }
                .getOrElse { error ->
                    return if ((error as? FirebaseFirestoreException)?.code ==
                        FirebaseFirestoreException.Code.PERMISSION_DENIED
                    ) {
                        EventAttendeesResult.Unavailable
                    } else {
                        EventAttendeesResult.Unknown
                    }
                }

        val uids = rsvps.documents.map { it.id }
        if (uids.isEmpty()) return EventAttendeesResult.Loaded(emptyList())

        // The RSVP doc carries only { status, updatedAt } (the rules pin it to
        // exactly those keys), so names/avatars are joined from the public
        // users/{uid} profile — readable by any authenticated user.
        //
        // Issued CONCURRENTLY: this is up to MAX_RENDERED (50) separate gets,
        // and awaiting them one at a time would serialize 50 round trips into
        // one worst-case latency on a mobile network. awaitAll preserves the
        // uids order, so the roster is assembled exactly as before — only the
        // waiting overlaps. (Individual document gets, not a whereIn query, so
        // the owner-or-admin rules evaluation per doc is unchanged.)
        val attendees =
            coroutineScope {
                uids
                    .map { uid ->
                        async {
                            val profile =
                                runCatchingCancellable {
                                    firestore.collection(USERS).document(uid).get().awaitResult()
                                }
                                    .getOrNull()
                            // One unreadable/missing profile degrades to a nameless row
                            // rather than failing the whole roster — they ARE going, which
                            // is the fact this section exists to report.
                            EventAttendee(
                                uid = uid,
                                displayName = profile?.getString("displayName"),
                                avatarPath = profile?.getString("avatarPath"),
                            )
                        }
                    }
                    .awaitAll()
            }
        return EventAttendeesResult.Loaded(attendees)
    }

    companion object {
        private const val EVENTS = "events"
        private const val DETAILS = "details"
        private const val PRIVATE = "private"
        private const val RSVPS = "rsvps"
        private const val USERS = "users"
        private const val REGION = "europe-west1"
        private const val CREATE = "events-create"

        fun createIfAvailable(context: Context): EventsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseEventsRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

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
        approximateArea = getString("approximateArea") ?: "",
        isOfficial = getBoolean("isOfficial") ?: false,
        status = status,
        counts = counts,
    )
}

private fun DocumentSnapshot.toEventDetail(): EventDetail? {
    if (!exists()) return null
    return EventDetail(
        description = getString("description"),
        locationName = getString("locationName"),
        address = getString("address"),
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
    )
}
