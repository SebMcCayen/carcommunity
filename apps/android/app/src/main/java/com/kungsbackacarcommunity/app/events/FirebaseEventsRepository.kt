package com.kungsbackacarcommunity.app.events

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
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
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("events-create failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val EVENTS = "events"
        private const val DETAILS = "details"
        private const val PRIVATE = "private"
        private const val RSVPS = "rsvps"
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
