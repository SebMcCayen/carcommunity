package com.kungsbackacarcommunity.app.chatchannels

import kotlinx.coroutines.flow.Flow

/** One convoy the caller can chat in (an ACCEPTED-member convoy), as surfaced to the UI. */
data class ChatConvoy(
    val convoyId: String,
    val title: String?,
    val status: String,
    val memberCount: Int,
    /**
     * Display names of the ACCEPTED members (owner included), in roster order.
     * Used to build a meaningful row title for these otherwise-unnamed convoys.
     * May be shorter than [memberCount] when some members have no display name.
     */
    val memberNames: List<String> = emptyList(),
    /**
     * When the convoy was created, epoch millis (from the doc's `createdAt`), or
     * null when the payload carried no parseable timestamp. Drives the row's
     * "created at" label and the newest-first ordering within each section.
     */
    val createdAtMillis: Long? = null,
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
 * whose chat rules/callables permit. ENDED convoys are KEPT (their channel stays
 * member-readable after the drive ends), so the list can show them as history;
 * the ongoing-vs-past split is a presentation concern handled by
 * [ConvoyRowFormat.group], not a filter here. Kept pure so it is unit-testable
 * off-device.
 */
object ConvoyChatMapper {
    fun chatEligibleConvoys(data: Map<String, Any?>?): List<ChatConvoy> {
        val convoys = (data?.get("convoys") as? List<*>).orEmpty()
        return convoys.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val convoyId = (map["convoyId"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val status = map["status"] as? String ?: "forming"
            val viewer = map["viewer"] as? Map<*, *>
            val inviteStatus = viewer?.get("inviteStatus") as? String
            if (inviteStatus != "accepted") return@mapNotNull null

            // Accepted members (owner included) carry the names the row is titled
            // with. `members` is the denormalized roster the callable already
            // returns, so no per-row profile fetch is needed. Fall back to the
            // memberUids count when the roster is absent/partial.
            val members = (map["members"] as? List<*>).orEmpty()
            val acceptedNames = members.mapNotNull { member ->
                val entry = member as? Map<*, *> ?: return@mapNotNull null
                if (entry["inviteStatus"] as? String != "accepted") return@mapNotNull null
                (entry["displayName"] as? String)?.takeIf { it.isNotBlank() }
            }
            val acceptedCount = members.count {
                (it as? Map<*, *>)?.get("inviteStatus") as? String == "accepted"
            }
            val memberCount = if (acceptedCount > 0) {
                acceptedCount
            } else {
                (map["memberUids"] as? List<*>)?.count { it is String } ?: 0
            }

            ChatConvoy(
                convoyId = convoyId,
                title = (map["title"] as? String)?.takeIf { it.isNotBlank() },
                status = status,
                memberCount = memberCount,
                memberNames = acceptedNames,
                createdAtMillis = parseIsoMillis(map["createdAt"] as? String),
            )
        }
    }

    /** Parses an ISO-8601 instant to epoch millis, or null when absent/unparseable. */
    private fun parseIsoMillis(iso: String?): Long? {
        val value = iso?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun List<*>?.orEmpty(): List<*> = this ?: emptyList<Any?>()
}
