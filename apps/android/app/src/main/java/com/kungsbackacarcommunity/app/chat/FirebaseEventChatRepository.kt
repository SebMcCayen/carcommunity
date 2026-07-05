package com.kungsbackacarcommunity.app.chat

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [EventChatRepository] backed by a Firestore snapshot listener on the messages
 * subcollection plus the events.* chat callables (europe-west1), Phase 12
 * slice 10. Removed messages keep their (blanked) body but carry
 * moderationState=removed, which the UI renders as a neutral placeholder.
 * Construction is guarded ([createIfAvailable] returns null without Firebase).
 */
class FirebaseEventChatRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : EventChatRepository {

    override fun observeMessages(eventId: String): Flow<ChatMessagesState> = callbackFlow {
        val registration =
            firestore
                .collection(EVENTS)
                .document(eventId)
                .collection(MESSAGES)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(ChatMessagesState.Error)
                        return@addSnapshotListener
                    }
                    val messages = snapshot?.documents?.mapNotNull { it.toChatMessage() } ?: emptyList()
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

    private suspend fun call(name: String, data: Map<String, Any>): Unit =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$name failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val EVENTS = "events"
        private const val MESSAGES = "messages"
        private const val POST_MESSAGE = "events-postChatMessage"
        private const val REPORT_MESSAGE = "events-reportChatMessage"

        fun createIfAvailable(context: Context): EventChatRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseEventChatRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
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
