package com.kungsbackacarcommunity.app.chat

import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.RsvpStatus

/**
 * Event-chat domain model + pure logic (Phase 12 slice 10).
 *
 * Mirrors the backend chat-core contract: the message limit, the moderation
 * states (visible/removed → the client renders a neutral placeholder for
 * removed), the report-reason enum, and the participation predicate (active
 * member + published event + going/maybe RSVP). Pure Kotlin — JVM-testable.
 */

/** Report reasons — mirror CHAT_MESSAGE_REPORT_REASONS. */
enum class ChatReportReason(val wire: String) {
    HARASSMENT("harassment"),
    HATE_OR_ABUSE("hate_or_abuse"),
    SPAM("spam"),
    UNSAFE_DRIVING("unsafe_driving"),
    PRIVACY("privacy"),
    OTHER("other"),
}

/**
 * Message moderation state (events/{id}/messages/{id}.moderationState).
 *
 * Mirrors the backend state machine: VISIBLE / ALLOWED render the body
 * normally (ALLOWED = an admin cleared a hide, so it is visible again);
 * AUTO_HIDDEN renders a collapsed "Show reported message" placeholder that the
 * reader can reveal locally; REMOVED renders a permanent moderator-removed
 * placeholder with no reveal.
 */
enum class ChatModerationState(val wire: String) {
    VISIBLE("visible"),
    AUTO_HIDDEN("auto_hidden"),
    REMOVED("removed"),
    ALLOWED("allowed"),
    ;

    companion object {
        fun fromWire(value: String?): ChatModerationState =
            values().firstOrNull { it.wire == value } ?: VISIBLE
    }
}

/**
 * A rendered chat message.
 * - [isRemoved] → permanent moderator-removed placeholder (no body, no reveal).
 * - [isAutoHidden] → collapsed "Show reported message" placeholder; the reader
 *   can reveal the [message] locally (per-user, ephemeral — never persisted and
 *   never un-hidden for anyone else).
 * A message is never both removed and auto-hidden.
 */
data class ChatMessage(
    val id: String,
    val authorUserId: String,
    val authorDisplayName: String?,
    val message: String,
    val isRemoved: Boolean,
    val isAutoHidden: Boolean = false,
    val createdAtMillis: Long?,
)

object EventChat {
    /** Backend CHAT_MESSAGE_MAX_LENGTH. */
    const val MESSAGE_MAX_LENGTH = 1000

    /**
     * Chat participation (read + post) requires passing the member gate, a
     * published event, and a going/maybe RSVP — mirrors guardChatParticipant
     * and the Firestore message read rule. The gate is switchable: while
     * member gating is disabled it admits any signed-in, non-suspended user,
     * and guardChatParticipant resolves the same way. The event-status and
     * RSVP requirements are NOT part of the gate and always apply.
     */
    fun canParticipate(
        passesMemberGate: Boolean,
        eventStatus: EventStatus?,
        rsvp: RsvpStatus?,
    ): Boolean =
        passesMemberGate &&
            eventStatus == EventStatus.PUBLISHED &&
            (rsvp == RsvpStatus.GOING || rsvp == RsvpStatus.MAYBE)

    /** Whether a draft message is within the 1..MAX bound after trimming. */
    fun isSendable(text: String): Boolean = text.trim().length in 1..MESSAGE_MAX_LENGTH

    /**
     * Whether the caller may block a message's author. False for the caller's
     * own messages — the backend rejects self-blocks anyway, and no block
     * affordance should ever appear on your own message. Directional: blocking
     * is one-way and never revealed to the target.
     */
    fun canBlock(message: ChatMessage, currentUid: String): Boolean =
        message.authorUserId != currentUid

    /**
     * Client-side display filter (Phase 12): drops every message whose
     * [ChatMessage.authorUserId] is in [blockedUids]. This is display-only —
     * server-side enforcement is a separate parity row — and directional: it
     * hides authors the caller has blocked without revealing anything to those
     * authors. An empty set returns the list unchanged.
     *
     * Pure function: it has no notion of the caller. If the caller's own uid is
     * present in [blockedUids] their messages WILL be dropped, so excluding the
     * caller (you cannot block yourself) is the caller's responsibility and is
     * enforced at the call site in EventChatRoute.
     */
    fun filterBlocked(messages: List<ChatMessage>, blockedUids: Set<String>): List<ChatMessage> {
        if (blockedUids.isEmpty()) return messages
        return messages.filterNot { it.authorUserId in blockedUids }
    }
}
