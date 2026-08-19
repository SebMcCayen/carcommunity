package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Date

/**
 * A trap DRAIN that landed on the signed-in member — the victim side of a Crown
 * Hunt Spikmatta. It arrives in the member's OWN real-time inbox
 * `perkDrainEvents/{uid}/events` the instant they drive onto a rival's trap and
 * the server moves KP, and drives the on-screen "Du körde på en Spikmatta! −N KP"
 * pop + the phone vibration.
 *
 * DELIBERATELY ANONYMOUS: the server never writes the placer's uid into this
 * doc, so the readable victim signal can never expose who owns a trap (the trap
 * network + the audit trail stay backend-only). The victim only learns THAT they
 * were caught and how much KP they lost.
 */
data class PerkDrainEvent(
    /** The drain-event doc id — drives the overlay's re-trigger key so each pops once. */
    val id: String,
    /** KP the victim lost to the trap (a positive number; rendered as "−N KP"). */
    val amountKp: Int,
    val createdAtMillis: Long,
)

/**
 * The pure client decisions for the trap-trigger victim signal, with NO Firebase
 * or Android types so they are unit-testable off the composable (mirrors
 * live.WavePresence). The SERVER is the sole authority for whether a drain
 * happened; these only gate the local listener + the once-per-attach replay
 * guard.
 */
object PerkDrainPresence {
    /**
     * Whether the victim listener should be attached at all. Only while the perks
     * feature is ON (dark until crownHuntPerks flips) AND the member is sharing
     * live location — a drain can only fire on an accepted position sample, which
     * only happens while sharing, so listening otherwise would just burn a
     * snapshot listener for events that can never arrive.
     */
    fun shouldListen(perksEnabled: Boolean, isSharing: Boolean): Boolean =
        perksEnabled && isSharing

    /**
     * Whether a drain event is FRESH relative to the moment the listener attached
     * — mirrors the server-side `createdAt > since` query so a short-TTL doc that
     * is still present on (re)attach never replays as a fresh pop + buzz. Strictly
     * greater than, exactly like the Firestore `whereGreaterThan`.
     */
    fun isFresh(createdAtMillis: Long, sinceMillis: Long): Boolean =
        createdAtMillis > sinceMillis
}

/**
 * Streams trap-drain events delivered to the signed-in member's own inbox. A
 * Firebase-free interface so the wiring is testable; the map wires the concrete
 * [FirebasePerkDrainEventRepository] only behind the crownHuntPerks flag.
 */
interface PerkDrainEventRepository {
    /**
     * Emits drain events delivered to [uid]'s inbox STRICTLY AFTER [sinceMillis]
     * (the moment the caller subscribed), so events still inside their short TTL
     * are never replayed as fresh pops on attach. Only newly-ADDED docs are
     * emitted; a TTL-sweep REMOVED never fires.
     */
    fun observeIncomingDrains(uid: String, sinceMillis: Long): Flow<PerkDrainEvent>
}

/**
 * [PerkDrainEventRepository] backed by a live listener on
 * `perkDrainEvents/{uid}/events` (owner-only read; backend-only writes). Guarded
 * ([createIfAvailable]) so a config-less / CI build gets a null repository and no
 * trap-trigger UI is wired. Mirrors live.FirebaseWaveRepository's receive path.
 */
class FirebasePerkDrainEventRepository private constructor(
    private val firestore: FirebaseFirestore,
) : PerkDrainEventRepository {

    override fun observeIncomingDrains(uid: String, sinceMillis: Long): Flow<PerkDrainEvent> =
        callbackFlow {
            // Only events newer than the subscribe instant: the short-TTL docs still
            // present on attach must not replay as fresh pops. Ordered by createdAt so
            // ADDED changes arrive in drain order.
            val since = Timestamp(Date(sinceMillis))
            val registration =
                firestore
                    .collection(PERK_DRAIN_EVENTS)
                    .document(uid)
                    .collection(EVENTS)
                    .whereGreaterThan(CREATED_AT, since)
                    .orderBy(CREATED_AT, Query.Direction.ASCENDING)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null) return@addSnapshotListener
                        for (change in snapshot.documentChanges) {
                            // A drain is a one-shot ADD: MODIFIED/REMOVED (a TTL sweep)
                            // never re-pop. The client never writes this inbox, so a
                            // pending-write is impossible, but mirror the wave channel's
                            // belt-and-braces guard.
                            if (change.type != DocumentChange.Type.ADDED) continue
                            if (change.document.metadata.hasPendingWrites()) continue
                            change.document.toPerkDrainEvent()?.let { trySend(it) }
                        }
                    }
            awaitClose { registration.remove() }
        }

    companion object {
        private const val PERK_DRAIN_EVENTS = "perkDrainEvents"
        private const val EVENTS = "events"
        private const val CREATED_AT = "createdAt"

        fun createIfAvailable(context: Context): PerkDrainEventRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePerkDrainEventRepository(FirebaseFirestore.getInstance())
        }
    }
}

/** Maps a drain-event document to an event, dropping malformed rows. */
private fun DocumentSnapshot.toPerkDrainEvent(): PerkDrainEvent? {
    val amount = getLong("amount") ?: return null
    val createdAt = getTimestamp("createdAt") ?: return null
    return PerkDrainEvent(
        id = id,
        amountKp = amount.toInt(),
        createdAtMillis = createdAt.toDate().time,
    )
}
