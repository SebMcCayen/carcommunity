package com.kungsbackacarcommunity.app.chatchannels

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.blocking.BlockVisibility
import com.kungsbackacarcommunity.app.blocking.BlockVisibilityRepository
import com.kungsbackacarcommunity.app.blocking.FirebaseBlockVisibilityRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

/**
 * [CommunityChatRepository] backed by the member-readable
 * `communityChat/global/messages` listener plus the member-gated
 * `communityChat-*` callables (europe-west1). Guarded ([createIfAvailable]) so a
 * config-less build gets a null repository and the screen renders a placeholder.
 *
 * The newest-window listener is bounded (createdAt descending, capped at
 * [CHANNEL_MESSAGES_PAGE_SIZE]) so it never syncs the whole channel; older pages
 * come from `communityChat-list`. Unread combines a newest-message listener with
 * the owner-readable `userPrivate/{uid}.communityChatLastReadAt` marker.
 *
 * BLOCKING: both the live window and the unread dot are filtered against the
 * caller's mutual-hidden set ([BlockVisibilityRepository]) so a blocked pair
 * never sees each other's messages, whichever side blocked. Older pages are
 * filtered SERVER-side by `communityChat-list`; the live window cannot be,
 * because a Firestore rule cannot filter a list query per document — see
 * [BlockVisibility] for the full enforcement map.
 */
class FirebaseCommunityChatRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val blockVisibility: BlockVisibilityRepository,
) : CommunityChatRepository {

    private fun messagesQuery(limit: Long): Query =
        firestore
            .collection(COMMUNITY)
            .document(CHANNEL_ID)
            .collection(MESSAGES)
            .orderBy(CREATED_AT, Query.Direction.DESCENDING)
            .limit(limit)

    override fun observeMessages(): Flow<ChannelMessagesState> =
        combine(observeRawMessages(), blockVisibility.observeHiddenUids()) { state, hidden ->
            // The hidden set is loaded ONCE per session (one document listener),
            // never per message, so the filter costs no reads at all.
            when (state) {
                is ChannelMessagesState.Loaded ->
                    ChannelMessagesState.Loaded(
                        BlockVisibility.filterHiddenAuthors(state.messages, hidden) { it.senderUid },
                    )
                ChannelMessagesState.Loading -> state
            }
        }

    private fun observeRawMessages(): Flow<ChannelMessagesState> = callbackFlow {
        val registration =
            messagesQuery(CHANNEL_MESSAGES_PAGE_SIZE.toLong())
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if ((error as? FirebaseFirestoreException)?.code ==
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                            // Access revoked (lost membership / blocked): emit the
                            // empty/denied state, clearing to no messages even when a
                            // cached snapshot exists, so denied history is never
                            // shown. Applies whether or not a snapshot came back (a
                            // first-load deny also clears to empty rather than
                            // staying in Loading). PERMISSION_DENIED is terminal — no
                            // retry undoes it.
                            trySend(ChannelMessagesState.Loaded(emptyList()))
                        } else if (snapshot != null) {
                            // Transient failure (UNAVAILABLE/timeout) WITH cached
                            // data: prefer the last-known messages; the SDK retries
                            // and delivers a fresh snapshot.
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

    override fun observeUnread(uid: String): Flow<Boolean> {
        // The window is [UNREAD_SCAN_LIMIT], not 1, because the newest message may
        // be from a blocked party: lighting the dot for a message the user will
        // never be shown sends them into an apparently unchanged channel. Scanning
        // a few and taking the newest VISIBLE one fixes that for any realistic
        // case; the documented bound is that if ALL of the newest
        // [UNREAD_SCAN_LIMIT] messages are hidden, an older unread message does
        // not light the dot. The channel itself still shows it.
        val newestWindow: Flow<List<ChannelMessage>?> = callbackFlow {
            val registration =
                messagesQuery(UNREAD_SCAN_LIMIT).addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if ((error as? FirebaseFirestoreException)?.code ==
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                            // Access revoked: hard-clear unread even if a stale
                            // cached snapshot is present — never keep the dot lit
                            // for a channel the user can no longer read.
                            trySend(null)
                            return@addSnapshotListener
                        }
                        // Other (transient) error with no cached data: keep the
                        // last-known value rather than emitting a misleading
                        // no-unread. With cached data, fall through and use it.
                        if (snapshot == null) return@addSnapshotListener
                    }
                    // Newest-first (the query is createdAt DESC), so the first
                    // visible entry downstream is the newest visible message.
                    trySend(snapshot?.documents?.mapNotNull { it.toChannelMessage() }.orEmpty())
                }
            awaitClose { registration.remove() }
        }
        val newest: Flow<ChannelMessage?> =
            combine(newestWindow, blockVisibility.observeHiddenUids()) { window, hidden ->
                window?.let { BlockVisibility.newestVisible(it, hidden) { m -> m.senderUid } }
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
                            // as missing, which could wrongly flip unread.
                            return@addSnapshotListener
                        }
                        trySend(snapshot?.getTimestamp(LAST_READ_AT)?.toDate()?.time)
                    }
            awaitClose { registration.remove() }
        }
        return combine(newest, lastReadAt) { message, marker ->
            ChannelThread.hasUnread(message, uid, marker)
        }
    }

    override suspend fun post(text: String, mentionedUids: List<String>): ChannelSendResult =
        functions.callChannel(
            POST,
            // `mentionedUids` is optional in the contract, so omit it entirely
            // when there are none rather than sending an empty array. Deduped and
            // capped here as well as in the composer, so no client state can reach
            // the server's one hard reject (> MAX_MESSAGE_MENTIONS).
            buildMap<String, Any?> {
                put("text", text.trim())
                val uids = mentionedUids.distinct().take(MAX_MESSAGE_MENTIONS)
                if (uids.isNotEmpty()) put("mentionedUids", uids)
            },
        ).fold(
            onSuccess = { ChannelResponseParser.parsePostSuccess(it) },
            onFailure = {
                ChannelSendResult.Failed(ChannelErrorMapper.mapSend(it.toChannelErrorCode()))
            },
        )

    override suspend fun loadOlder(before: String): ChannelOlderResult =
        functions.callChannel(LIST, mapOf("before" to before)).fold(
            onSuccess = { ChannelOlderResult.Loaded(ChannelResponseParser.parseMessagesPage(it)) },
            onFailure = { ChannelOlderResult.Failed },
        )

    override suspend fun markRead() {
        // Best-effort idempotent bookkeeping; a transient failure is swallowed.
        functions.callChannel(MARK_READ, emptyMap())
    }

    companion object {
        private const val COMMUNITY = "communityChat"
        private const val CHANNEL_ID = "global"
        private const val MESSAGES = "messages"
        private const val CREATED_AT = "createdAt"
        private const val USER_PRIVATE = "userPrivate"
        private const val LAST_READ_AT = "communityChatLastReadAt"
        private const val POST = "communityChat-post"
        private const val LIST = "communityChat-list"
        private const val MARK_READ = "communityChat-markRead"

        /** Newest-message window scanned for the unread dot (see observeUnread). */
        private const val UNREAD_SCAN_LIMIT = 10L

        fun createIfAvailable(context: Context): CommunityChatRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseCommunityChatRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(CHANNEL_FUNCTIONS_REGION),
                FirebaseBlockVisibilityRepository.createOrEmpty(context),
            )
        }
    }
}
