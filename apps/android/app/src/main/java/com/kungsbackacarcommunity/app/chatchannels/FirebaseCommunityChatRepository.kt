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
import com.kungsbackacarcommunity.app.profile.FirebaseLiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
 *
 * LIVE PROFILES: a message carries the sender's name/avatar as they were at post
 * time, stamped on by `communityChat-post` and never rewritten. Both the live
 * window and older pages are overlaid with the sender's current `users/{uid}`
 * profile ([LiveProfileRepository], [ChannelThread.hydrate]) so a member who
 * changes their avatar changes it on their whole history — de-duplicated by
 * sender, never a read per message.
 */
class FirebaseCommunityChatRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val blockVisibility: BlockVisibilityRepository,
    private val liveProfiles: LiveProfileRepository,
) : CommunityChatRepository {

    private fun messagesQuery(limit: Long): Query =
        firestore
            .collection(COMMUNITY)
            .document(CHANNEL_ID)
            .collection(MESSAGES)
            .orderBy(CREATED_AT, Query.Direction.DESCENDING)
            .limit(limit)

    @OptIn(ExperimentalCoroutinesApi::class)
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
            // Overlay each sender's CURRENT profile onto the copy stamped on the
            // message at post time (ChannelThread.hydrate explains the decision).
            //
            // De-duplicated by sender BEFORE the read, so a full window costs a
            // read per distinct sender rather than per message. Runs after the
            // block filter, so a hidden sender is never paid for.
            .flatMapLatest { state ->
                if (state !is ChannelMessagesState.Loaded) return@flatMapLatest flowOf(state)
                val uids = LiveProfiles.uidsOf(state.messages) { it.senderUid }
                liveProfiles.observeProfiles(uids).map { live ->
                    ChannelMessagesState.Loaded(ChannelThread.hydrate(state.messages, live))
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

    override suspend fun post(
        text: String,
        mentionedUids: List<String>,
        clientId: String?,
    ): ChannelSendResult =
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
                // Only sent when present, so an omitted key is a legacy auto-id doc
                // rather than a null the strict backend schema would reject.
                if (clientId != null) put("clientId", clientId)
            },
        ).fold(
            onSuccess = { ChannelResponseParser.parsePostSuccess(it) },
            onFailure = {
                ChannelSendResult.Failed(ChannelErrorMapper.mapSend(it.toChannelErrorCode()))
            },
        )

    override suspend fun loadOlder(before: String): ChannelOlderResult =
        functions.callChannel(LIST, mapOf("before" to before)).fold(
            onSuccess = {
                val page = ChannelResponseParser.parseMessagesPage(it)
                // An older page carries the same frozen sender copies as the live
                // window, so it needs the same overlay — otherwise scrolling back
                // would show a member's old avatar above their new one. Usually
                // free: the window's senders are already cached from the live
                // hydration above.
                val live =
                    liveProfiles.loadProfiles(
                        LiveProfiles.uidsOf(page.messages) { message -> message.senderUid },
                    )
                ChannelOlderResult.Loaded(
                    page.copy(messages = ChannelThread.hydrate(page.messages, live)),
                )
            },
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
                FirebaseLiveProfileRepository.createOrEmpty(context),
            )
        }
    }
}
