package com.kungsbackacarcommunity.app.chatchannels

import kotlinx.coroutines.flow.Flow

/** One convoy the caller can chat in (an ACCEPTED-member convoy), as surfaced to the UI. */
data class ChatConvoy(
    val convoyId: String,
    val title: String?,
    val status: String,
    val memberCount: Int,
)

/** UI-facing state of the caller's convoy list (the Convoys tab). */
sealed interface ConvoyListState {
    data object Loading : ConvoyListState

    /** The `convoy-list` callable failed transiently — retryable. */
    data object Error : ConvoyListState

    data class Loaded(val convoys: List<ChatConvoy>) : ConvoyListState
}

/**
 * Per-convoy chat access. The convoy list comes from the member-gated
 * `convoy-list` callable (filtered client-side to ACCEPTED-member convoys — the
 * only ones whose chat the caller may read/post). Messages read from a live
 * Firestore listener on `convoyChats/{convoyId}/messages` (rules grant reads only
 * to accepted members); sending + older-page pagination go through the
 * `convoyChat-*` callables (europe-west1). Firebase-free so it is testable with
 * fakes (mirrors DmRepository / CommunityChatRepository).
 */
interface ConvoyChatRepository {
    /** `convoy-list` — the caller's ACCEPTED-member convoys (chat-eligible), newest-first. */
    suspend fun listConvoys(): ConvoyListState

    /** Live newest-window of a convoy's channel, chronological. */
    fun observeMessages(convoyId: String): Flow<ChannelMessagesState>

    /** `convoyChat-post` — posts [text] to [convoyId]'s channel. */
    suspend fun post(convoyId: String, text: String): ChannelSendResult

    /** `convoyChat-list` — an older page of [convoyId] before the [before] ISO cursor. */
    suspend fun loadOlder(convoyId: String, before: String): ChannelOlderResult
}

/**
 * Pure projection of a `convoy-list` payload into the chat-eligible convoy rows:
 * keeps only convoys the caller has ACCEPTED (owner included) — the only ones
 * whose chat rules/callables permit — and drops ended convoys (their channel is
 * historical, not an active chat). Kept pure so it is unit-testable off-device.
 */
object ConvoyChatMapper {
    fun chatEligibleConvoys(data: Map<String, Any?>?): List<ChatConvoy> {
        val convoys = (data?.get("convoys") as? List<*>).orEmpty()
        return convoys.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val convoyId = (map["convoyId"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val status = map["status"] as? String ?: "forming"
            if (status == "ended") return@mapNotNull null
            val viewer = map["viewer"] as? Map<*, *>
            val inviteStatus = viewer?.get("inviteStatus") as? String
            if (inviteStatus != "accepted") return@mapNotNull null
            val memberUids = (map["memberUids"] as? List<*>)?.count { it is String } ?: 0
            ChatConvoy(
                convoyId = convoyId,
                title = (map["title"] as? String)?.takeIf { it.isNotBlank() },
                status = status,
                memberCount = memberUids,
            )
        }
    }

    private fun List<*>?.orEmpty(): List<*> = this ?: emptyList<Any?>()
}
