package com.kungsbackacarcommunity.app.chatchannels

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
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
                        // Prefer cached data over collapsing to empty; a genuine
                        // transient failure is retried by the SDK, so keep the
                        // last emitted state rather than misrendering "no messages".
                        if (snapshot != null) {
                            val cached =
                                snapshot.documents.mapNotNull { it.toChannelMessage() }.asReversed()
                            trySend(ChannelMessagesState.Loaded(cached))
                        } else {
                            // No cached snapshot (e.g. first load + PERMISSION_DENIED
                            // / UNAVAILABLE): there is no prior state to keep, so
                            // surface the empty/denied state instead of leaving the
                            // UI stuck in Loading forever. The SDK still retries and
                            // delivers a fresh snapshot once the listen succeeds.
                            trySend(ChannelMessagesState.Loaded(emptyList()))
                        }
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
                messagesQuery(1L).addSnapshotListener { snapshot, _ ->
                    trySend(snapshot?.documents?.firstOrNull()?.toChannelMessage())
                }
            awaitClose { registration.remove() }
        }
        val lastReadAt: Flow<Long?> = callbackFlow {
            val registration =
                firestore
                    .collection(USER_PRIVATE)
                    .document(uid)
                    .addSnapshotListener { snapshot, _ ->
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
