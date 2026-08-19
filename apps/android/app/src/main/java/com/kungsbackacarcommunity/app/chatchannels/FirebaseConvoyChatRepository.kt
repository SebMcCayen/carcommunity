package com.kungsbackacarcommunity.app.chatchannels

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.blocking.BlockVisibility
import com.kungsbackacarcommunity.app.blocking.BlockVisibilityRepository
import com.kungsbackacarcommunity.app.blocking.FirebaseBlockVisibilityRepository
import com.kungsbackacarcommunity.app.profile.FirebaseLiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

/**
 * [ConvoyChatRepository] backed by the `convoy-list` callable (for the caller's
 * chat-eligible convoys), the accepted-member-readable
 * `convoyChats/{convoyId}/messages` listener, and the `convoyChat-*` callables
 * (europe-west1). Guarded ([createIfAvailable]) so a config-less build gets a
 * null repository and the tab renders a placeholder.
 *
 * BLOCKING: the live window is filtered against the caller's mutual-hidden set
 * ([BlockVisibilityRepository]), so two members of the same convoy who have
 * blocked each other stop seeing each other's messages in both directions.
 * Older pages are filtered SERVER-side by `convoyChat-list`; the live window
 * cannot be, because a Firestore rule cannot filter a list query per document —
 * see [BlockVisibility].
 *
 * LIVE PROFILES: as in the community channel, the sender profile stamped on each
 * message is overlaid with the sender's current `users/{uid}` profile
 * ([LiveProfileRepository], [ChannelThread.hydrate]) on both the live window and
 * older pages.
 *
 * UNREAD: [observeUnread] combines its OWN bounded newest-message listener with
 * the owner-readable `userPrivate/{uid}.convoyChatLastReadAt.{convoyId}` marker —
 * community's design, generalized from a dot to a count. Both listeners are
 * separate from [observeMessages] on purpose: the badge is shown by the map
 * shell, where the channel itself is not open, so tying the count to the
 * channel's window would mean either syncing a chat nobody has opened or no badge
 * at all.
 */
class FirebaseConvoyChatRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val blockVisibility: BlockVisibilityRepository,
    private val liveProfiles: LiveProfileRepository,
) : ConvoyChatRepository {

    override suspend fun listConvoys(): ConvoyListState =
        functions.callChannel(CONVOY_LIST, emptyMap()).fold(
            onSuccess = { ConvoyListState.Loaded(ConvoyChatMapper.chatEligibleConvoys(it)) },
            onFailure = { ConvoyListState.Error },
        )

    override fun observeMessages(convoyId: String): Flow<ChannelMessagesState> =
        combine(observeRawMessages(convoyId), blockVisibility.observeHiddenUids()) { state, hidden ->
            // One document listener supplies the hidden set for the whole
            // session; the filter itself costs no reads.
            when (state) {
                is ChannelMessagesState.Loaded ->
                    ChannelMessagesState.Loaded(
                        BlockVisibility.filterHiddenAuthors(state.messages, hidden) { it.senderUid },
                    )
                ChannelMessagesState.Loading -> state
            }
        }
            .hydrateSenders(liveProfiles)

    private fun observeRawMessages(convoyId: String): Flow<ChannelMessagesState> = callbackFlow {
        val registration =
            firestore
                .collection(CONVOY_CHATS)
                .document(convoyId)
                .collection(MESSAGES)
                .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                .limit(CHANNEL_MESSAGES_PAGE_SIZE.toLong())
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if ((error as? FirebaseFirestoreException)?.code ==
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                            // Removed from the convoy / blocked: emit the empty/denied
                            // state, clearing to no messages even when a cached
                            // snapshot exists, so denied convoy history is never
                            // shown. Applies whether or not a snapshot came back (a
                            // first-load deny also clears to empty rather than staying
                            // in Loading). PERMISSION_DENIED is terminal — no retry
                            // undoes it.
                            trySend(ChannelMessagesState.Loaded(emptyList()))
                        } else if (snapshot != null) {
                            // Transient failure (UNAVAILABLE/timeout) WITH cached
                            // data: prefer the last-known messages; the SDK retries.
                            val cached =
                                snapshot.documents.mapNotNull { it.toChannelMessage() }.asReversed()
                            trySend(ChannelMessagesState.Loaded(cached))
                        }
                        // Transient failure with NO cached data: don't emit — an
                        // empty list would misrender offline/unavailable as "no
                        // messages". The SDK retries; the initial Loading holds
                        // until a real snapshot arrives.
                        return@addSnapshotListener
                    }
                    val messages =
                        snapshot?.documents?.mapNotNull { it.toChannelMessage() }
                            ?.asReversed().orEmpty()
                    trySend(ChannelMessagesState.Loaded(messages))
                }
        awaitClose { registration.remove() }
    }

    override fun observeUnread(convoyId: String, uid: String): Flow<Int> {
        // The window is [UNREAD_SCAN_LIMIT], and it is what makes the count both
        // cheap and honest. It is ONE more than the badge's display cap
        // (ConvoyBar.UNREAD_DISPLAY_MAX), so anything at or past the cap renders
        // as "9+" whether the true backlog is 10 or 10 000 — the saturated count
        // and the real one are indistinguishable on screen, and nothing pays for a
        // count() aggregation or a wider sync to tell them apart. The documented
        // bound is that this can never render an exact number above the cap.
        val window: Flow<List<ChannelMessage>?> = callbackFlow {
            val registration =
                firestore
                    .collection(CONVOY_CHATS)
                    .document(convoyId)
                    .collection(MESSAGES)
                    .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                    .limit(UNREAD_SCAN_LIMIT)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            if ((error as? FirebaseFirestoreException)?.code ==
                                FirebaseFirestoreException.Code.PERMISSION_DENIED
                            ) {
                                // Removed from the convoy / blocked: hard-clear the
                                // badge even if a stale cached snapshot is present.
                                // Never leave a count lit for a chat the caller can
                                // no longer open.
                                trySend(null)
                                return@addSnapshotListener
                            }
                            // Other (transient) error with no cached data: keep the
                            // last-known count rather than emitting a misleading
                            // zero. With cached data, fall through and use it.
                            if (snapshot == null) return@addSnapshotListener
                        }
                        trySend(snapshot?.documents?.mapNotNull { it.toChannelMessage() }.orEmpty())
                    }
            awaitClose { registration.remove() }
        }
        // Blocked authors are filtered out of the count for the same reason the
        // channel filters them out of its live window: counting a message the
        // caller will never be shown sends them into an apparently unchanged chat.
        val visible: Flow<List<ChannelMessage>> =
            combine(window, blockVisibility.observeHiddenUids()) { messages, hidden ->
                if (messages == null) {
                    emptyList()
                } else {
                    BlockVisibility.filterHiddenAuthors(messages, hidden) { it.senderUid }
                }
            }
        val lastReadAt: Flow<Long?> = callbackFlow {
            val registration =
                firestore
                    .collection(USER_PRIVATE)
                    .document(uid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null && snapshot == null) {
                            // Transient failure with no cached marker: keep the
                            // last-known marker rather than momentarily reading it
                            // as missing, which would wrongly re-light the badge.
                            return@addSnapshotListener
                        }
                        // A map keyed by convoy id (see convoyChat.markRead): an
                        // absent entry means "never opened", which counts every
                        // message from someone else.
                        val markers = snapshot?.get(CONVOY_LAST_READ_AT) as? Map<*, *>
                        trySend((markers?.get(convoyId) as? Timestamp)?.toDate()?.time)
                    }
            awaitClose { registration.remove() }
        }
        return combine(visible, lastReadAt) { messages, marker ->
            ChannelThread.unreadCount(messages, uid, marker)
        }
    }

    override fun observeAnyUnread(uid: String): Flow<Boolean> = callbackFlow {
        // ONE listener on the caller's own private document supplies BOTH maps the
        // derivation needs — the per-convoy newest-message stamps
        // (convoyChatLatestAt, maintained by the convoyChat.post fan-out) and the
        // per-convoy last-read markers (convoyChatLastReadAt, stamped by markRead).
        // No per-convoy message listener is opened, so this is a single document
        // read regardless of how many convoys the caller is in.
        val registration =
            firestore
                .collection(USER_PRIVATE)
                .document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null && snapshot == null) {
                        // Transient failure with no cached doc: keep the last-known
                        // value rather than momentarily reading both maps as empty,
                        // which would wrongly clear (or fail to light) the dot.
                        return@addSnapshotListener
                    }
                    val latest = timestampMillisByConvoy(snapshot?.get(CONVOY_LATEST_AT))
                    val lastRead = timestampMillisByConvoy(snapshot?.get(CONVOY_LAST_READ_AT))
                    trySend(ChannelThread.anyConvoyUnread(latest, lastRead))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun markRead(convoyId: String) {
        // Best-effort idempotent bookkeeping; a transient failure is swallowed.
        functions.callChannel(MARK_READ, mapOf("convoyId" to convoyId))
    }

    override suspend fun post(convoyId: String, text: String, clientId: String?): ChannelSendResult =
        post(convoyId, text, clientId, replyToMessageId = null)

    override suspend fun post(
        convoyId: String,
        text: String,
        clientId: String?,
        replyToMessageId: String?,
    ): ChannelSendResult =
        functions.callChannel(
            POST,
            buildMap {
                put("convoyId", convoyId)
                put("text", text.trim())
                // Only sent when present, so an omitted key is a legacy auto-id doc
                // rather than a null the strict backend schema would reject.
                if (clientId != null) put("clientId", clientId)
                // Inline reply target — snapshotted server-side (and ignored while
                // chatReplies is off).
                if (replyToMessageId != null) put("replyToMessageId", replyToMessageId)
            },
        ).fold(
            onSuccess = { ChannelResponseParser.parsePostSuccess(it) },
            onFailure = {
                ChannelSendResult.Failed(ChannelErrorMapper.mapSend(it.toChannelErrorCode()))
            },
        )

    override suspend fun loadOlder(convoyId: String, before: String): ChannelOlderResult =
        functions.callChannel(LIST, mapOf("convoyId" to convoyId, "before" to before)).fold(
            onSuccess = {
                ChannelOlderResult.Loaded(
                    ChannelResponseParser.parseMessagesPage(it).hydrateSenders(liveProfiles),
                )
            },
            onFailure = { ChannelOlderResult.Failed },
        )

    companion object {
        private const val CONVOY_CHATS = "convoyChats"
        private const val MESSAGES = "messages"
        private const val CREATED_AT = "createdAt"
        private const val USER_PRIVATE = "userPrivate"
        private const val CONVOY_LAST_READ_AT = "convoyChatLastReadAt"
        private const val CONVOY_LATEST_AT = "convoyChatLatestAt"

        /**
         * Reads a `{ [convoyId]: Timestamp }` map field into `convoyId -> epoch
         * millis`, dropping any entry whose key or value is not the expected shape
         * (a legacy/partial document can never be written by the client but may
         * still hold junk). Shared by both per-convoy maps on userPrivate.
         */
        private fun timestampMillisByConvoy(raw: Any?): Map<String, Long> {
            val map = raw as? Map<*, *> ?: return emptyMap()
            return buildMap {
                for ((key, value) in map) {
                    val convoyId = key as? String ?: continue
                    val millis = (value as? Timestamp)?.toDate()?.time ?: continue
                    put(convoyId, millis)
                }
            }
        }
        private const val CONVOY_LIST = "convoy-list"
        private const val POST = "convoyChat-post"
        private const val LIST = "convoyChat-list"
        private const val MARK_READ = "convoyChat-markRead"

        /**
         * Newest-message window scanned for the unread count (see observeUnread).
         * One more than ConvoyBar.UNREAD_DISPLAY_MAX, so the count saturates
         * exactly where the badge starts showing "9+".
         */
        private const val UNREAD_SCAN_LIMIT = 10L

        fun createIfAvailable(context: Context): ConvoyChatRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseConvoyChatRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(CHANNEL_FUNCTIONS_REGION),
                FirebaseBlockVisibilityRepository.createOrEmpty(context),
                FirebaseLiveProfileRepository.sharedOrEmpty(context),
            )
        }
    }
}
