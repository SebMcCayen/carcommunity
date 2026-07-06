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

/** Message moderation state (events/{id}/messages/{id}.moderationState). */
enum class ChatModerationState(val wire: String) {
    VISIBLE("visible"),
    REMOVED("removed"),
    ;

    companion object {
        fun fromWire(value: String?): ChatModerationState =
            values().firstOrNull { it.wire == value } ?: VISIBLE
    }
}

/** A rendered chat message. [isRemoved] → show a neutral placeholder, not body. */
data class ChatMessage(
    val id: String,
    val authorUserId: String,
    val authorDisplayName: String?,
    val message: String,
    val isRemoved: Boolean,
    val createdAtMillis: Long?,
)

object EventChat {
    /** Backend CHAT_MESSAGE_MAX_LENGTH. */
    const val MESSAGE_MAX_LENGTH = 1000

    /**
     * Chat participation (read + post) requires an active member, a published
     * event, and a going/maybe RSVP — mirrors guardChatParticipant and the
     * Firestore message read rule.
     */
    fun canParticipate(
        isActiveMember: Boolean,
        eventStatus: EventStatus?,
        rsvp: RsvpStatus?,
    ): Boolean =
        isActiveMember &&
            eventStatus == EventStatus.PUBLISHED &&
            (rsvp == RsvpStatus.GOING || rsvp == RsvpStatus.MAYBE)

    /** Whether a draft message is within the 1..MAX bound after trimming. */
    fun isSendable(text: String): Boolean = text.trim().length in 1..MESSAGE_MAX_LENGTH
}
