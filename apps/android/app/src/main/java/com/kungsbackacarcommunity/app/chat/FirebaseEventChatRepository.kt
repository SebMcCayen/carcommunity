package com.kungsbackacarcommunity.app.chat

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.blocking.BlockVisibility
import com.kungsbackacarcommunity.app.blocking.BlockVisibilityRepository
import com.kungsbackacarcommunity.app.blocking.FirebaseBlockVisibilityRepository
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

/**
 * [EventChatRepository] backed by a Firestore snapshot listener on the messages
 * subcollection plus the events.* chat callables (europe-west1), Phase 12
 * slice 10. Removed messages keep their (blanked) body but carry
 * moderationState=removed, which the UI renders as a neutral placeholder.
 * Construction is guarded ([createIfAvailable] returns null without Firebase).
 *
 * BLOCKING: filtered against the caller's mutual-hidden set
 * ([BlockVisibilityRepository]) so a blocked pair does not see each other in an
 * event's chat, in either direction. This surface has NO list callable — it is a
 * snapshot listener only — so unlike the community/convoy channels the filter
 * here is client-side ONLY. See [BlockVisibility] for why a Firestore rule
 * cannot do it and what that does and does not guarantee.
 */
class FirebaseEventChatRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val blockVisibility: BlockVisibilityRepository,
) : EventChatRepository {

    override fun observeMessages(eventId: String): Flow<ChatMessagesState> =
        combine(observeRawMessages(eventId), blockVisibility.observeHiddenUids()) { state, hidden ->
            // One document listener supplies the hidden set for the session; the
            // filter itself costs no reads.
            when (state) {
                is ChatMessagesState.Loaded ->
                    ChatMessagesState.Loaded(
                        BlockVisibility.filterHiddenAuthors(state.messages, hidden) {
                            it.authorUserId
                        },
                    )
                else -> state
            }
        }

    private fun observeRawMessages(eventId: String): Flow<ChatMessagesState> = callbackFlow {
        // Bound the read to the most recent messages (backend-domain-mapping
        // note): newest-first with a limit, then reverse to chronological for
        // display, so the listener cost stays flat as a chat grows.
        val registration =
            firestore
                .collection(EVENTS)
                .document(eventId)
                .collection(MESSAGES)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(MESSAGE_PAGE_SIZE)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(ChatMessagesState.Error)
                        return@addSnapshotListener
                    }
                    val messages =
                        snapshot?.documents?.mapNotNull { it.toChatMessage() }?.asReversed() ?: emptyList()
                    trySend(ChatMessagesState.Loaded(messages))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun postMessage(eventId: String, message: String) {
        call(POST_MESSAGE, mapOf("eventId" to eventId, "message" to message.trim()))
    }

    override suspend fun reportMessage(
        eventId: String,
        messageId: String,
        reason: ChatReportReason,
        details: String?,
    ) {
        val data =
            buildMap<String, Any> {
                put("eventId", eventId)
                put("messageId", messageId)
                put("reason", reason.wire)
                details?.trim()?.takeIf { it.isNotEmpty() }?.let { put("details", it) }
            }
        call(REPORT_MESSAGE, data)
    }

    private suspend fun call(name: String, data: Map<String, Any>) {
        functions.getHttpsCallable(name).call(data)
            .awaitOrThrow { "$name failed without a cause" }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val EVENTS = "events"
        private const val MESSAGES = "messages"
        private const val POST_MESSAGE = "events-postChatMessage"
        private const val REPORT_MESSAGE = "events-reportChatMessage"

        /** Cap the live chat read to the most recent messages. */
        private const val MESSAGE_PAGE_SIZE = 50L

        fun createIfAvailable(context: Context): EventChatRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseEventChatRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
                FirebaseBlockVisibilityRepository.createOrEmpty(context),
            )
        }
    }
}

private fun DocumentSnapshot.toChatMessage(): ChatMessage? {
    if (!exists()) return null
    val authorUserId = getString("authorUserId") ?: return null
    val isRemoved = ChatModerationState.fromWire(getString("moderationState")) == ChatModerationState.REMOVED
    return ChatMessage(
        id = id,
        authorUserId = authorUserId,
        authorDisplayName = getString("authorDisplayName"),
        message = getString("message") ?: "",
        isRemoved = isRemoved,
        createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
    )
}
