package com.kungsbackacarcommunity.app.chat

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the message stream. */
sealed interface ChatMessagesState {
    data object Loading : ChatMessagesState

    data object Error : ChatMessagesState

    data class Loaded(val messages: List<ChatMessage>) : ChatMessagesState
}

/**
 * Event-chat operations (Phase 12 slice 10). Firebase-free interface so the
 * screen/coordinator logic is unit- and UI-testable with fakes.
 *
 * Reads are a Firestore snapshot listener on the messages subcollection
 * (rules require active member + published + going/maybe RSVP). Posting and
 * reporting go through the member-gated callables — no client writes to
 * messages/.
 */
interface EventChatRepository {
    /** Live message stream, oldest first; Loading until the first snapshot. */
    fun observeMessages(eventId: String): Flow<ChatMessagesState>

    /** events.postChatMessage — posts a plain-text message (<=1000 chars). */
    suspend fun postMessage(eventId: String, message: String)

    /** events.reportChatMessage — reports a message for moderation. */
    suspend fun reportMessage(
        eventId: String,
        messageId: String,
        reason: ChatReportReason,
        details: String?,
    )
}
