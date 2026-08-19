package com.kungsbackacarcommunity.app.convoy

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A reaction that arrived on the convoy channel, ready to pop on screen. */
data class ConvoyReactionEvent(
    /** The reaction document id — drives the overlay's re-trigger key. */
    val id: String,
    val kind: ConvoyReactionKind,
    val senderUid: String,
    val senderName: String?,
    val createdAtMillis: Long,
)

/** Outcome of a `convoy-sendReaction` call. */
sealed interface ConvoyReactionSendResult {
    /** The reaction was broadcast (or idempotently replayed). */
    data object Sent : ConvoyReactionSendResult

    /**
     * The SERVER refused the send as too soon — the anti-spam cooldown. [retryAfterMs]
     * is the server's exact remaining window (0 when unknown), which the button uses
     * to grey for precisely the right time.
     */
    data class RateLimited(val kind: ConvoyReactionKind, val retryAfterMs: Long) :
        ConvoyReactionSendResult

    /** Any other failure (offline, not a member any more, transient). Retryable. */
    data object Failed : ConvoyReactionSendResult
}

/**
 * Convoy REACTIONS — the transient "flash your lights" broadcasts (police alert /
 * hello-goodbye / follow-me). Sending is the member-gated `convoy-sendReaction`
 * callable; receiving is a live Firestore listener on the convoy-scoped
 * `convoyChats/{convoyId}/reactions` subcollection — the SAME real-time channel
 * the convoy chat uses (accepted-member read gate), so no parallel presence
 * system is opened. Firebase-free interface for testability.
 */
interface ConvoyReactionRepository {
    /**
     * Broadcasts [kind] to [convoyId]. [clientId] is the idempotency key so a
     * retried optimistic send never double-pops receivers. The SERVER enforces the
     * anti-spam cooldown; a refusal comes back as [ConvoyReactionSendResult.RateLimited].
     */
    suspend fun send(
        convoyId: String,
        kind: ConvoyReactionKind,
        clientId: String,
    ): ConvoyReactionSendResult

    /**
     * Emits reactions broadcast to [convoyId] STRICTLY AFTER [sinceMillis] — the
     * moment the caller subscribed — so historical reactions still inside their
     * short TTL are never replayed as fresh pops on attach. Only newly-ADDED
     * documents are emitted; unknown/legacy kinds are dropped.
     */
    fun observeReactions(convoyId: String, sinceMillis: Long): Flow<ConvoyReactionEvent>
}

/**
 * [ConvoyReactionRepository] backed by the `convoy-sendReaction` callable
 * (europe-west1) and a live listener on `convoyChats/{convoyId}/reactions`.
 * Guarded ([createIfAvailable]) so a config-less / CI build gets a null
 * repository and no reaction UI is wired.
 */
class FirebaseConvoyReactionRepository private constructor(
    private val functions: FirebaseFunctions,
    private val firestore: FirebaseFirestore,
) : ConvoyReactionRepository {

    override suspend fun send(
        convoyId: String,
        kind: ConvoyReactionKind,
        clientId: String,
    ): ConvoyReactionSendResult =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(SEND_REACTION)
                .call(convoySendReactionPayload(convoyId, kind, clientId))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    continuation.resume(
                        if (task.isSuccessful) {
                            ConvoyReactionSendResult.Sent
                        } else {
                            task.exception.toReactionSendResult(kind)
                        },
                    )
                }
        }

    override fun observeReactions(convoyId: String, sinceMillis: Long): Flow<ConvoyReactionEvent> =
        callbackFlow {
            // Only reactions newer than the subscribe instant: the short-TTL docs
            // still present on attach must not replay as fresh pops. Ordered by
            // createdAt so ADDED changes arrive in send order.
            val since = Timestamp(java.util.Date(sinceMillis))
            val registration =
                firestore
                    .collection(CONVOY_CHATS)
                    .document(convoyId)
                    .collection(REACTIONS)
                    .whereGreaterThan(CREATED_AT, since)
                    .orderBy(CREATED_AT, Query.Direction.ASCENDING)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null) return@addSnapshotListener
                        for (change in snapshot.documentChanges) {
                            // A reaction is a one-shot ADD: MODIFIED/REMOVED (a TTL
                            // sweep) never pop. Also skip local writes still pending
                            // (the sender pops off its own accepted server doc, once).
                            if (change.type != DocumentChange.Type.ADDED) continue
                            if (change.document.metadata.hasPendingWrites()) continue
                            change.document.toReactionEvent()?.let { trySend(it) }
                        }
                    }
            awaitClose { registration.remove() }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val SEND_REACTION = "convoy-sendReaction"
        private const val CONVOY_CHATS = "convoyChats"
        private const val REACTIONS = "reactions"
        private const val CREATED_AT = "createdAt"

        fun createIfAvailable(context: Context): ConvoyReactionRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseConvoyReactionRepository(
                FirebaseFunctions.getInstance(REGION),
                FirebaseFirestore.getInstance(),
            )
        }
    }
}

/** Maps a `convoy-sendReaction` failure to a result; RESOURCE_EXHAUSTED = the cooldown. */
private fun Throwable?.toReactionSendResult(kind: ConvoyReactionKind): ConvoyReactionSendResult {
    val functionsError = this as? FirebaseFunctionsException ?: return ConvoyReactionSendResult.Failed
    return if (functionsError.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) {
        ConvoyReactionSendResult.RateLimited(kind, functionsError.reactionRetryAfterMs())
    } else {
        ConvoyReactionSendResult.Failed
    }
}

/** Reads `details.retryAfterMs` off the callable error, or 0 when absent/malformed. */
private fun FirebaseFunctionsException.reactionRetryAfterMs(): Long {
    val details = details as? Map<*, *> ?: return 0L
    return (details["retryAfterMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
}

/** Maps a reaction document to an event, dropping unknown/legacy kinds. */
private fun com.google.firebase.firestore.DocumentSnapshot.toReactionEvent(): ConvoyReactionEvent? {
    val kind = ConvoyReactionKind.fromWire(getString("kind")) ?: return null
    val senderUid = getString("senderUid") ?: return null
    val createdAt = getTimestamp("createdAt") ?: return null
    return ConvoyReactionEvent(
        id = id,
        kind = kind,
        senderUid = senderUid,
        senderName = getString("senderDisplayName"),
        createdAtMillis = createdAt.toDate().time,
    )
}
