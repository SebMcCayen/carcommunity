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

/**
 * [ConvoyChatRepository] backed by the `convoy-list` callable (for the caller's
 * chat-eligible convoys), the accepted-member-readable
 * `convoyChats/{convoyId}/messages` listener, and the `convoyChat-*` callables
 * (europe-west1). Guarded ([createIfAvailable]) so a config-less build gets a
 * null repository and the tab renders a placeholder.
 */
class FirebaseConvoyChatRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : ConvoyChatRepository {

    override suspend fun listConvoys(): ConvoyListState =
        functions.callChannel(CONVOY_LIST, emptyMap()).fold(
            onSuccess = { ConvoyListState.Loaded(ConvoyChatMapper.chatEligibleConvoys(it)) },
            onFailure = { ConvoyListState.Error },
        )

    override fun observeMessages(convoyId: String): Flow<ChannelMessagesState> = callbackFlow {
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

    override suspend fun post(convoyId: String, text: String, clientId: String?): ChannelSendResult =
        functions.callChannel(
            POST,
            buildMap {
                put("convoyId", convoyId)
                put("text", text.trim())
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

    override suspend fun loadOlder(convoyId: String, before: String): ChannelOlderResult =
        functions.callChannel(LIST, mapOf("convoyId" to convoyId, "before" to before)).fold(
            onSuccess = { ChannelOlderResult.Loaded(ChannelResponseParser.parseMessagesPage(it)) },
            onFailure = { ChannelOlderResult.Failed },
        )

    companion object {
        private const val CONVOY_CHATS = "convoyChats"
        private const val MESSAGES = "messages"
        private const val CREATED_AT = "createdAt"
        private const val CONVOY_LIST = "convoy-list"
        private const val POST = "convoyChat-post"
        private const val LIST = "convoyChat-list"

        fun createIfAvailable(context: Context): ConvoyChatRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseConvoyChatRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(CHANNEL_FUNCTIONS_REGION),
            )
        }
    }
}
