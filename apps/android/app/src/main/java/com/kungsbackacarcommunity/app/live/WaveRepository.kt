package com.kungsbackacarcommunity.app.live

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Date
import kotlin.coroutines.resume

/**
 * The wave cooldown mirror, in milliseconds — kept in lock-step with the SERVER's
 * WAVE_COOLDOWN_MS (functions/src/live/wave-core.ts). The server is the source of
 * truth (it refuses an early send and returns the exact remaining window); this is
 * only the optimistic client greying so the icon dims the instant you tap rather
 * than after a round-trip.
 */
const val WAVE_COOLDOWN_MS: Long = 45_000L

/** A wave that arrived in the caller's inbox, ready to pop mid-screen. */
data class WaveEvent(
    /** The shared wave document id — drives the overlay's re-trigger key. */
    val id: String,
    val senderUid: String,
    /** The sender's public display name, or null (renders as an anonymous wave). */
    val senderName: String?,
    val createdAtMillis: Long,
)

/** Outcome of a `live-sendWave` call. */
sealed interface WaveSendResult {
    /** The wave was broadcast (or idempotently replayed). [recipientCount] is how many nearby sharers got it. */
    data class Sent(val recipientCount: Int) : WaveSendResult

    /**
     * The SERVER refused the send as too soon — the anti-spam cooldown. [retryAfterMs]
     * is the server's exact remaining window (0 when unknown), which the icon uses to
     * grey for precisely the right time.
     */
    data class RateLimited(val retryAfterMs: Long) : WaveSendResult

    /**
     * The caller is not sharing live location (the server has no trustworthy origin
     * to broadcast from). The icon should not have been tappable; treated as a no-op.
     */
    data object NotSharing : WaveSendResult

    /** Any other failure (offline, transient). Retryable. */
    data object Failed : WaveSendResult
}

/**
 * WAVE to nearby live users — a transient "👋" a live sharer broadcasts to every
 * OTHER live sharer within range. Sending is the `live-sendWave` callable (which
 * reads the sender's authoritative position, finds nearby sharers, and enforces
 * the anti-spam cooldown SERVER-side); receiving is a live Firestore listener on
 * the caller's OWN per-user inbox `liveWaves/{uid}/waves` (owner-only read). Reuses
 * the existing live-discovery substrate rather than opening a parallel presence
 * system. Firebase-free interface for testability.
 */
interface WaveRepository {
    /**
     * Broadcasts a wave to nearby live sharers. [clientId] is the idempotency key so
     * a retried optimistic send never double-pops receivers. The SERVER enforces the
     * anti-spam cooldown; a refusal comes back as [WaveSendResult.RateLimited].
     */
    suspend fun send(clientId: String): WaveSendResult

    /**
     * Emits waves delivered to [uid]'s inbox STRICTLY AFTER [sinceMillis] — the
     * moment the caller subscribed — so waves still inside their short TTL are never
     * replayed as fresh pops on attach. Only newly-ADDED documents are emitted.
     */
    fun observeIncomingWaves(uid: String, sinceMillis: Long): Flow<WaveEvent>
}

/**
 * [WaveRepository] backed by the `live-sendWave` callable (europe-west1) and a live
 * listener on `liveWaves/{uid}/waves`. Guarded ([createIfAvailable]) so a
 * config-less / CI build gets a null repository and no wave UI is wired.
 */
class FirebaseWaveRepository private constructor(
    private val functions: FirebaseFunctions,
    private val firestore: FirebaseFirestore,
) : WaveRepository {

    override suspend fun send(clientId: String): WaveSendResult =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(SEND_WAVE)
                .call(mapOf(CLIENT_ID to clientId))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    continuation.resume(
                        if (task.isSuccessful) {
                            val data = task.result?.data as? Map<*, *>
                            val count = (data?.get(RECIPIENT_COUNT) as? Number)?.toInt() ?: 0
                            WaveSendResult.Sent(count)
                        } else {
                            task.exception.toWaveSendResult()
                        },
                    )
                }
        }

    override fun observeIncomingWaves(uid: String, sinceMillis: Long): Flow<WaveEvent> =
        callbackFlow {
            // Only waves newer than the subscribe instant: the short-TTL docs still
            // present on attach must not replay as fresh pops. Ordered by createdAt so
            // ADDED changes arrive in send order.
            val since = Timestamp(Date(sinceMillis))
            val registration =
                firestore
                    .collection(LIVE_WAVES)
                    .document(uid)
                    .collection(WAVES)
                    .whereGreaterThan(CREATED_AT, since)
                    .orderBy(CREATED_AT, Query.Direction.ASCENDING)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null) return@addSnapshotListener
                        for (change in snapshot.documentChanges) {
                            // A wave is a one-shot ADD: MODIFIED/REMOVED (a TTL sweep)
                            // never pop. Skip local pending writes (there are none here —
                            // clients never write this inbox — but mirror the reaction
                            // channel's belt-and-braces guard).
                            if (change.type != DocumentChange.Type.ADDED) continue
                            if (change.document.metadata.hasPendingWrites()) continue
                            change.document.toWaveEvent()?.let { trySend(it) }
                        }
                    }
            awaitClose { registration.remove() }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val SEND_WAVE = "live-sendWave"
        private const val LIVE_WAVES = "liveWaves"
        private const val WAVES = "waves"
        private const val CREATED_AT = "createdAt"
        private const val CLIENT_ID = "clientId"
        private const val RECIPIENT_COUNT = "recipientCount"

        fun createIfAvailable(context: Context): WaveRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseWaveRepository(
                FirebaseFunctions.getInstance(REGION),
                FirebaseFirestore.getInstance(),
            )
        }
    }
}

/** Maps a `live-sendWave` failure to a result; RESOURCE_EXHAUSTED = the cooldown. */
private fun Throwable?.toWaveSendResult(): WaveSendResult {
    val functionsError = this as? FirebaseFunctionsException ?: return WaveSendResult.Failed
    return when (functionsError.code) {
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
            WaveSendResult.RateLimited(functionsError.waveRetryAfterMs())
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> WaveSendResult.NotSharing
        else -> WaveSendResult.Failed
    }
}

/** Reads `details.retryAfterMs` off the callable error, or 0 when absent/malformed. */
private fun FirebaseFunctionsException.waveRetryAfterMs(): Long {
    val details = details as? Map<*, *> ?: return 0L
    return (details["retryAfterMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
}

/** Maps a wave document to an event, dropping malformed rows. */
private fun DocumentSnapshot.toWaveEvent(): WaveEvent? {
    val senderUid = getString("senderUid") ?: return null
    val createdAt = getTimestamp("createdAt") ?: return null
    return WaveEvent(
        id = id,
        senderUid = senderUid,
        senderName = getString("senderDisplayName"),
        createdAtMillis = createdAt.toDate().time,
    )
}
