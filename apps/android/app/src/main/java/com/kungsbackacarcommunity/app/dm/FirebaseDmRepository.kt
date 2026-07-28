package com.kungsbackacarcommunity.app.dm

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.blocking.BlockVisibility
import com.kungsbackacarcommunity.app.blocking.BlockVisibilityRepository
import com.kungsbackacarcommunity.app.blocking.FirebaseBlockVisibilityRepository
import com.kungsbackacarcommunity.app.profile.FirebaseLiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfiles
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [DmRepository] backed by member-readable Firestore listeners plus the
 * member-gated `dm-*` callables (europe-west1). Guarded ([createIfAvailable])
 * so a config-less build gets a null repository and the screens render a
 * loading placeholder.
 *
 * The raw Firestore `DocumentSnapshot` → pure-model translation (with Firebase
 * `Timestamp`s extracted to epoch millis) lives here so [DmMapper] / [DmThread]
 * stay testable off-device. HttpsError codes (never messages) are translated to
 * the pure [DmErrorCode] and mapped by [DmErrorMapper].
 *
 * The inbox query is bounded newest-first (`lastMessageAt` descending, capped at
 * [DM_CONVERSATIONS_QUERY_LIMIT]) so the listener never syncs/holds the full
 * conversation set; this relies on the `members` array-contains + `lastMessageAt`
 * descending composite index (firebase/firestore.indexes.json). Rows are
 * additionally sorted client-side via [DmMapper.sortConversations].
 *
 * BLOCKING: a blocked pair's thread disappears for BOTH parties.
 *  - The THREAD listener needs nothing here: firebase/firestore.rules denies the
 *    messages subcollection outright for a blocked pair (it can, because every
 *    message in one conversation shares the same pair), and the existing
 *    PERMISSION_DENIED branch below already renders that as an empty thread.
 *  - The INBOX row is dropped here, client-side, because the inbox is a LIST
 *    query and a Firestore rule cannot filter one per document without failing
 *    the whole query. The document is therefore still delivered — but the
 *    blocking-onBlockWrite trigger blanks its `lastMessage` preview while the
 *    block stands (functions/src/dm/blockedConversation.ts), so the delivered
 *    copy carries no counterparty content.
 *
 *    TWO independent signals drop that row, matching dm.listConversations on the
 *    server: the caller's `blockVisibility` hidden set, and the `blockedPair`
 *    marker stored on the conversation itself. The hidden set is
 *    trigger-maintained (so briefly behind a fresh block) and stops growing at
 *    MAX_HIDDEN_UIDS; the marker covers both gaps, and costs nothing because it
 *    rides on a document the listener already receives.
 *
 * LIVE PROFILES: the counterparty's name/avatar on an inbox row comes from
 * `memberProfiles`, a denormalized copy that `dm.sendMessage` refreshes only for
 * the SENDER — so the other party's card is frozen until they next message you.
 * [LiveProfileRepository] overlays their current `users/{uid}` profile at read
 * time ([DmMapper.hydrateConversations]). It is done HERE and not in
 * `dm.listConversations` because this listener, not that callable, is what the
 * inbox actually renders.
 */
class FirebaseDmRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val blockVisibility: BlockVisibilityRepository,
    private val liveProfiles: LiveProfileRepository,
) : DmRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeConversations(uid: String): Flow<DmConversationsState> =
        combine(observeRawConversations(uid), blockVisibility.observeHiddenUids()) { state, hidden ->
            when (state) {
                is DmConversationsState.Loaded ->
                    DmConversationsState.Loaded(
                        BlockVisibility.filterHiddenAuthors(state.conversations, hidden) {
                            it.otherUser.uid
                        },
                    )
                else -> state
            }
        }
            // Overlay each counterparty's CURRENT profile onto the denormalized
            // copy the conversation document carries (DmMapper.hydrateConversations
            // explains why that copy is stale for the other party).
            //
            // flatMapLatest rather than combine, because the uid set is DERIVED
            // from the rows: a new conversation must trigger a read for its
            // counterparty. Superseding the previous lookup is the behaviour we
            // want — an inbox update makes the in-flight read for the older row set
            // obsolete. Hydration runs AFTER the block filter so a hidden row is
            // never paid for with a profile read.
            .flatMapLatest { state ->
                if (state !is DmConversationsState.Loaded) return@flatMapLatest flowOf(state)
                val uids = LiveProfiles.uidsOf(state.conversations) { it.otherUser.uid }
                liveProfiles.observeProfiles(uids).map { live ->
                    DmConversationsState.Loaded(
                        DmMapper.hydrateConversations(state.conversations, live),
                    )
                }
            }

    private fun observeRawConversations(uid: String): Flow<DmConversationsState> = callbackFlow {
        val registration =
            firestore
                .collection(CONVERSATIONS)
                .whereArrayContains(MEMBERS, uid)
                .orderBy(LAST_MESSAGE_AT, Query.Direction.DESCENDING)
                .limit(DM_CONVERSATIONS_QUERY_LIMIT.toLong())
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // Firestore can deliver cached data ALONGSIDE an error;
                        // keep the usable inbox instead of flickering to Error on
                        // a transient failure (mirrors observeThread).
                        if (snapshot != null) {
                            val cached =
                                snapshot.documents.mapNotNull { it.toConversation(uid) }
                            trySend(
                                DmConversationsState.Loaded(DmMapper.sortConversations(cached)),
                            )
                            return@addSnapshotListener
                        }
                        // No snapshot to fall back on — surface a retryable
                        // error, tagged with the Firestore code (e.g.
                        // FAILED_PRECONDITION for a missing composite index) so
                        // the route can report it for diagnostics.
                        trySend(
                            DmConversationsState.Error(
                                (error as? FirebaseFirestoreException)?.code?.name,
                            ),
                        )
                        return@addSnapshotListener
                    }
                    val rows =
                        snapshot?.documents?.mapNotNull { it.toConversation(uid) }.orEmpty()
                    trySend(DmConversationsState.Loaded(DmMapper.sortConversations(rows)))
                }
        awaitClose { registration.remove() }
    }

    override fun observeThread(conversationId: String): Flow<DmThreadState> = callbackFlow {
        val registration =
            firestore
                .collection(CONVERSATIONS)
                .document(conversationId)
                .collection(MESSAGES)
                .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                .limit(DM_MESSAGES_PAGE_SIZE.toLong())
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // Firestore can deliver cached data ALONGSIDE an error;
                        // prefer that over collapsing the thread to empty.
                        if (snapshot != null) {
                            val cached =
                                snapshot.documents.mapNotNull { it.toMessage() }.asReversed()
                            trySend(DmThreadState.Loaded(cached))
                            return@addSnapshotListener
                        }
                        // A self-derived pairId whose conversation doc doesn't
                        // exist yet denies the messages listen (the rule get()s
                        // the missing parent) with PERMISSION_DENIED. That's an
                        // empty, not-yet-started thread — surface it as such so
                        // the caller can send the first message; the route
                        // re-subscribes once the doc exists.
                        if ((error as? FirebaseFirestoreException)?.code ==
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                            trySend(DmThreadState.Loaded(emptyList()))
                            return@addSnapshotListener
                        }
                        // Any OTHER error (UNAVAILABLE, network, etc.) is
                        // transient: keep the last emitted state instead of
                        // misrendering it as "no messages". Don't close the flow
                        // — the SDK retries and will deliver a fresh snapshot.
                        return@addSnapshotListener
                    }
                    val messages =
                        snapshot?.documents?.mapNotNull { it.toMessage() }?.asReversed().orEmpty()
                    trySend(DmThreadState.Loaded(messages))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult =
        callForData(
            SEND_MESSAGE,
            // Only include clientId when present, so the payload stays byte-identical
            // to the legacy shape for a null (the strict backend schema rejects a
            // literal null on the optional field).
            buildMap {
                put("toUid", toUid)
                put("text", text.trim())
                if (clientId != null) put("clientId", clientId)
            },
        ).fold(
            onSuccess = { DmResponseParser.parseSendSuccess(it) },
            onFailure = { DmSendResult.Failed(DmErrorMapper.mapSend(it.toDmErrorCode())) },
        )

    override suspend fun loadOlder(conversationId: String, before: String): DmOlderResult =
        callForData(
            GET_MESSAGES,
            mapOf("conversationId" to conversationId, "before" to before),
        ).fold(
            onSuccess = { DmOlderResult.Loaded(DmResponseParser.parseMessagesPage(it)) },
            // A failed older-page is a TRANSIENT error, not end-of-pagination:
            // report it as such so the coordinator keeps the "load older"
            // affordance for a retry instead of permanently ending the thread.
            onFailure = { DmOlderResult.Failed },
        )

    override suspend fun markRead(conversationId: String) {
        // Best-effort: a not-found (never-created conversation) or transient
        // failure is swallowed — marking read is idempotent bookkeeping.
        callForData(MARK_READ, mapOf("conversationId" to conversationId))
    }

    private suspend fun callForData(
        name: String,
        payload: Map<String, Any?>,
    ): Result<Map<String, Any?>?> =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(payload)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        val data = task.result?.getData() as? Map<String, Any?>
                        continuation.resume(Result.success(data))
                    } else {
                        continuation.resume(
                            Result.failure(
                                task.exception
                                    ?: IllegalStateException("$name failed without a cause"),
                            ),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val CONVERSATIONS = "conversations"
        private const val MESSAGES = "messages"
        private const val MEMBERS = "members"
        private const val CREATED_AT = "createdAt"
        private const val LAST_MESSAGE_AT = "lastMessageAt"
        private const val SEND_MESSAGE = "dm-sendMessage"
        private const val GET_MESSAGES = "dm-getMessages"
        private const val MARK_READ = "dm-markRead"

        fun createIfAvailable(context: Context): DmRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseDmRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
                FirebaseBlockVisibilityRepository.createOrEmpty(context),
                FirebaseLiveProfileRepository.createOrEmpty(context),
            )
        }
    }
}

/** Reads a stored conversation doc into the caller-oriented inbox row. */
private fun DocumentSnapshot.toConversation(callerUid: String): DmConversation? {
    if (!exists()) return null
    @Suppress("UNCHECKED_CAST")
    val members = (get("members") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    if (members.isEmpty()) return null

    val profilesRaw = get("memberProfiles") as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val memberProfiles =
        profilesRaw.entries.mapNotNull { (key, value) ->
            val uid = key as? String ?: return@mapNotNull null
            val map = value as? Map<*, *> ?: return@mapNotNull null
            uid to DmUser(uid, map["displayName"] as? String, map["avatarPath"] as? String)
        }.toMap()

    val unreadRaw = get("unread") as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val unread =
        unreadRaw.entries.mapNotNull { (key, value) ->
            val uid = key as? String ?: return@mapNotNull null
            uid to (value as? Number)?.toLong().orZero()
        }.toMap()

    val lastMessage = get("lastMessage") as? Map<*, *>
    val doc =
        DmConversationDoc(
            members = members,
            memberProfiles = memberProfiles,
            lastMessageText = lastMessage?.get("text") as? String,
            lastMessageSenderUid = lastMessage?.get("senderUid") as? String,
            lastMessageAtMillis = getTimestamp("lastMessageAt")?.toDate()?.time,
            unread = unread,
            blockedPair = getBoolean("blockedPair") == true,
        )
    // Dropped here rather than downstream so the row never reaches the UI: this
    // is the marker signal that covers the window before the blockVisibility
    // mirror catches up (and the case where the mirror is at its cap). See
    // DmMapper.isHiddenByBlock.
    if (DmMapper.isHiddenByBlock(doc)) return null
    return DmMapper.conversation(id, doc, callerUid)
}

/** Reads a stored message doc into the pure model (Timestamp → millis + ISO). */
private fun DocumentSnapshot.toMessage(): DmMessage? {
    if (!exists()) return null
    val senderUid = getString("senderUid") ?: return null
    val millis = getTimestamp("createdAt")?.toDate()?.time
    return DmMessage(
        id = id,
        senderUid = senderUid,
        text = getString("text") ?: "",
        createdAtMillis = millis,
        createdAtIso = millis?.let(::millisToIso),
        // Echoed idempotency key: lets the live snapshot reconcile against the
        // sender's optimistic bubble (doc id == clientId for optimistic sends).
        clientId = getString("clientId"),
    )
}

private fun Long?.orZero(): Long = this ?: 0L

/** Translates a raw callable failure into the pure, testable error code. */
private fun Throwable.toDmErrorCode(): DmErrorCode {
    val functionsError = this as? FirebaseFunctionsException ?: return DmErrorCode.Other
    return when (functionsError.code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> DmErrorCode.Unauthenticated
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> DmErrorCode.PermissionDenied
        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> DmErrorCode.InvalidArgument
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> DmErrorCode.FailedPrecondition
        FirebaseFunctionsException.Code.NOT_FOUND -> DmErrorCode.NotFound
        else -> DmErrorCode.Other
    }
}
