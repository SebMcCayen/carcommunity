package com.kungsbackacarcommunity.app.moderation

import kotlinx.coroutines.flow.Flow

/** Public surfaces are explicit: this capability cannot address PM, convoy or Driver club paths. */
data class PublicChatTarget(val source: String, val messageId: String, val eventId: String? = null, val expectedAuthorUid: String? = null) {
    init {
        require(source == "community" || source == "event")
        require(messageId.isNotBlank() && '/' !in messageId)
        require((source == "event") == !eventId.isNullOrBlank())
        require(eventId == null || '/' !in eventId)
    }

    fun payload(): Map<String, Any> = buildMap {
        put("source", source); put("messageId", messageId)
        eventId?.let { put("eventId", it) }
    }
}

enum class ChatAdminAction(val wire: String) { Remove("remove"), Pause("pause") }
enum class ChatPauseUnit(val wire: String) { Minutes("minutes"), Days("days") }

data class PublicChatModerationContext(
    val actorUid: String,
    val authorUid: String,
    val version: String,
    val canRemove: Boolean,
    val canPause: Boolean,
    val canWarn: Boolean,
    val maxPauseDays: Int = 30,
    val authorName: String? = null,
    val text: String? = null,
    val pauseUntilMillis: Long? = null,
    val serverNowMillis: Long = 0L,
    val observedElapsedMillis: Long = 0L,
)

data class ChatModerationDraft(
    val action: ChatAdminAction,
    val reason: String = "",
    val warn: Boolean = false,
    val addPause: Boolean = false,
    val amount: String = "",
    val unit: ChatPauseUnit = ChatPauseUnit.Minutes,
) {
    val pauses: Boolean get() = action == ChatAdminAction.Pause || addPause

    fun duration(maxDays: Int): Int? {
        // Reject signs, decimals, overflow and units with no corresponding number.
        if (amount.isEmpty() || amount.any { it !in '0'..'9' }) return null
        val value = amount.toLongOrNull() ?: return null
        val limit = if (unit == ChatPauseUnit.Days) maxDays.toLong() else maxDays.toLong() * 1440L
        return value.takeIf { it in 1..limit && it <= Int.MAX_VALUE }?.toInt()
    }

    fun isValid(context: PublicChatModerationContext): Boolean =
        reason.trim().length in 1..500 &&
            (action != ChatAdminAction.Remove || context.canRemove) &&
            (!warn || context.canWarn) &&
            (!pauses || (context.canPause && duration(context.maxPauseDays) != null))

    fun payload(target: PublicChatTarget, context: PublicChatModerationContext, operationId: String): Map<String, Any?> {
        require(isValid(context))
        require(operationId.isNotBlank())
        return mapOf(
            "target" to target.payload(), "expectedActorUid" to context.actorUid,
            "expectedVersion" to context.version, "action" to action.wire,
            "reason" to reason.trim(), "warn" to warn,
            "pause" to if (pauses) mapOf("amount" to duration(context.maxPauseDays), "unit" to unit.wire) else null,
            "idempotencyKey" to operationId,
        )
    }
}

fun previewPublicPauseUntil(context: PublicChatModerationContext, draft: ChatModerationDraft, elapsedMillis: Long): Long? {
    if (!draft.pauses) return null
    val amount = draft.duration(context.maxPauseDays) ?: return null
    val duration = amount.toLong() * if (draft.unit == ChatPauseUnit.Days) 86_400_000L else 60_000L
    val estimatedNow = context.serverNowMillis + (elapsedMillis - context.observedElapsedMillis).coerceAtLeast(0L)
    return maxOf(context.pauseUntilMillis ?: 0L, estimatedNow + duration)
}

data class PublicChatWritePause(val untilMillis: Long, val reason: String, val deadlineElapsedMillis: Long) {
    fun activeAt(elapsedMillis: Long): Boolean = elapsedMillis < deadlineElapsedMillis
}

/** A response is anchored to request start, so transport latency cannot prolong the pause UI. */
fun publicChatPauseDeadline(requestStartedElapsedMillis: Long, untilMillis: Long, serverNowMillis: Long): Long =
    requestStartedElapsedMillis + (untilMillis - serverNowMillis).coerceAtLeast(0L)

interface PublicChatModerationRepository {
    /** True only for fresh server profile data for this signed-in account. Not an authorization grant. */
    fun observeAdminCandidate(): Flow<Boolean>
    fun observeOwnPause(): Flow<PublicChatWritePause?>
    suspend fun context(target: PublicChatTarget): PublicChatModerationContext
    suspend fun decide(payload: Map<String, Any?>)
}

data class PublicChatModerationSession(
    val uid: String,
    val repository: PublicChatModerationRepository,
    val adminCandidate: Boolean,
    val pause: PublicChatWritePause? = null,
)
