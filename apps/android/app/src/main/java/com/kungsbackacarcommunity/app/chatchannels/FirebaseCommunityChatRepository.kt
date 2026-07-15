package com.kungsbackacarcommunity.app.chatchannels

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
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
 * come from `communityChat-list`. Unread combines a 1-message newest listener
 * with the owner-readable `userPrivate/{uid}.communityChatLastReadAt` marker.
 */
class FirebaseCommunityChatRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : CommunityChatRepository {

    private fun messagesQuery(limit: Long): Query =
        firestore
            .collection(COMMUNITY)
            .document(CHANNEL_ID)
            .collection(MESSAGES)
            .orderBy(CREATED_AT, Query.Direction.DESCENDING)
            .limit(limit)

    override fun observeMessages(): Flow<ChannelMessagesState> = callbackFlow {
        val registration =
            messagesQuery(CHANNEL_MESSAGES_PAGE_SIZE.toLong())
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if ((error as? FirebaseFirestoreException)?.code ==
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                            // Access revoked (lost membership / blocked): hard clear
                            // even if a cached snapshot exists, so denied history is
                            // never shown. This also leaves Loading on a first-load
                            // deny. PERMISSION_DENIED is terminal — no retry to undo.
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
        val newest: Flow<ChannelMessage?> = callbackFlow {
            val registration =
                messagesQuery(1L).addSnapshotListener { snapshot, error ->
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
                    trySend(snapshot?.documents?.firstOrNull()?.toChannelMessage())
                }
            awaitClose { registration.remove() }
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

    override suspend fun post(text: String): ChannelSendResult =
        functions.callChannel(POST, mapOf("text" to text.trim())).fold(
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

        fun createIfAvailable(context: Context): CommunityChatRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseCommunityChatRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(CHANNEL_FUNCTIONS_REGION),
            )
        }
    }
}
