package com.kungsbackacarcommunity.app.dm

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the live inbox (conversation list). */
sealed interface DmConversationsState {
    data object Loading : DmConversationsState

    /**
     * The inbox listener failed with no cached data to fall back on. [code] is
     * the Firestore error code name when known (e.g. "FAILED_PRECONDITION" for a
     * missing composite index, "UNAVAILABLE" when offline), used only for
     * diagnostics/error-reporting — the UI shows the same retryable message.
     */
    data class Error(val code: String? = null) : DmConversationsState

    data class Loaded(val conversations: List<DmConversation>) : DmConversationsState
}

/**
 * True when ANY conversation in a [DmConversationsState.Loaded] inbox has unread
 * messages for the caller — the aggregate "the Friends tab has something new"
 * boolean behind the map chat-bubble dot and the Friends tab dot. Loading/Error
 * are not-unread: a dot is a positive claim ("there IS something"), so an inbox
 * that has not loaded (or failed) shows none rather than a false one.
 *
 * Pure so the derivation is unit-testable off-device (mirrors
 * [com.kungsbackacarcommunity.app.notifications.Notifications.unreadCount]).
 */
fun DmConversationsState.anyUnread(): Boolean =
    this is DmConversationsState.Loaded && conversations.any { it.unreadCount > 0 }

/** UI-facing state of a live message thread (the newest window). */
sealed interface DmThreadState {
    data object Loading : DmThreadState

    data class Loaded(val messages: List<DmMessage>) : DmThreadState
}

/**
 * Direct-messaging access. Reads are live Firestore listeners (rules grant
 * member reads of `conversations` + `.../messages`); sending, marking-read, and
 * older-page pagination go through the member-gated `dm-*` callables
 * (europe-west1). Firebase-free interface so the coordinators/screens are
 * unit- and UI-testable with fakes.
 *
 * Note there is deliberately no `Error` case on [DmThreadState]: for a
 * self-derived `pairId` the only realistic listener failure is "the
 * conversation doc doesn't exist yet" (the messages read rule `get()`s the
 * parent conversation), which is not an error — it's an empty thread the caller
 * can start. Genuine transient failures are retried by the Firestore SDK.
 */
interface DmRepository {
    /** Live inbox for [uid], newest-first. Loading until the first snapshot. */
    fun observeConversations(uid: String): Flow<DmConversationsState>

    /** Live newest-window of a thread, chronological. Empty until the first message exists. */
    fun observeThread(conversationId: String): Flow<DmThreadState>

    /**
     * `dm-sendMessage` — sends to [toUid], creating the conversation on the first
     * message. [clientId] is the send idempotency key: it is used verbatim as the
     * message document id, so a resend of the SAME clientId (an optimistic retry)
     * is exactly-once server-side and the delivered document reconciles against
     * the local optimistic bubble by that key. Null keeps the legacy (auto-id,
     * non-idempotent) behaviour.
     */
    suspend fun sendMessage(toUid: String, text: String, clientId: String? = null): DmSendResult

    /**
     * `dm-getMessages` — an older page before the [before] ISO cursor (page 30).
     * Returns [DmOlderResult.Failed] on a transient callable failure so the
     * caller can distinguish it from a genuine end-of-pagination
     * ([DmMessagesPage.hasMore] == false) and offer a retry.
     */
    suspend fun loadOlder(conversationId: String, before: String): DmOlderResult

    /** `dm-markRead` — clears the caller's unread counter for the conversation. Idempotent. */
    suspend fun markRead(conversationId: String)
}
