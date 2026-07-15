package com.kungsbackacarcommunity.app.chatchannels

/**
 * Chat-channels domain (the COMMUNITY app-wide chat + per-CONVOY chats). The
 * backend (europe-west1 callables `communityChat-*` / `convoyChat-*`, plus
 * member-readable Firestore `communityChat/global/messages` and
 * `convoyChats/{convoyId}/messages`) is the source of truth; the client never
 * writes the message trees. Everything here is pure Kotlin so the
 * mapping/parsing/merge logic is JVM-unit-testable without Firebase (mirrors the
 * dm/ domain).
 *
 * The THREE product chats are Community (this domain), Convoy (this domain) and
 * Friends = the existing 1:1 DMs (com.kungsbackacarcommunity.app.dm — NOT
 * rebuilt here). Both channel messages share one denormalized shape:
 * `{ senderUid, text, createdAt, senderDisplayName, senderAvatarPath }` — the
 * sender's safe profile is stamped on each message so a channel renders with no
 * per-message profile lookup (channels have no bounded member set to key a
 * profile map on, unlike a DM conversation).
 */

/** Backend CHAT_MESSAGE_MAX_LENGTH (community + convoy share the 1..2000 cap). */
const val CHANNEL_MESSAGE_MAX_LENGTH = 2000

/** Backend CHAT_MESSAGES_PAGE_SIZE (newest-first window). */
const val CHANNEL_MESSAGES_PAGE_SIZE = 30

/**
 * One rendered channel message. [createdAtIso] is the pagination cursor for
 * older pages (the `before` argument the `*-list` callables expect). The
 * denormalized sender profile lets the channel render the author's name/avatar
 * without a lookup.
 */
data class ChannelMessage(
    val id: String,
    val senderUid: String,
    val text: String,
    val senderDisplayName: String?,
    val senderAvatarPath: String?,
    val createdAtMillis: Long?,
    val createdAtIso: String?,
)

/**
 * UI-facing state of a live channel message stream (the newest window). Like the
 * DM thread there is deliberately no `Error` case: a transient listener failure
 * is retried by the Firestore SDK and an empty/denied channel simply renders the
 * empty state; the coordinator surfaces send/paging failures separately.
 */
sealed interface ChannelMessagesState {
    data object Loading : ChannelMessagesState

    data class Loaded(val messages: List<ChannelMessage>) : ChannelMessagesState
}

/**
 * Canonical HttpsError codes we branch on, decoupled from Firebase's
 * `FirebaseFunctionsException.Code` so the mapping is testable on a plain JVM.
 * Any code we don't special-case collapses to [Other].
 */
enum class ChannelErrorCode {
    Unauthenticated,
    PermissionDenied,
    InvalidArgument,
    FailedPrecondition,
    NotFound,
    Other,
}

/**
 * A user-facing send failure category. [CannotDeliver] is deliberately neutral:
 * the backend returns `failed-precondition` (profile missing / still-invited
 * convoy member) and `not-found` (convoy gone / outsider probing) without a
 * client-facing discriminator, so both collapse to one neutral message.
 */
enum class ChannelSendError {
    SignedOut,
    NotMember,
    Invalid,
    CannotDeliver,
    Generic,
}

/** Outcome of a `*-post` callable. */
sealed interface ChannelSendResult {
    data class Sent(val messageId: String) : ChannelSendResult

    data class Failed(val error: ChannelSendError) : ChannelSendResult
}

/** A page of older messages from a `*-list` callable (newest-first within the page). */
data class ChannelMessagesPage(
    val messages: List<ChannelMessage>,
    val nextBefore: String?,
    val hasMore: Boolean,
)

/**
 * Outcome of an older-page load. A [Loaded] page carries the backend's own
 * `hasMore` (a genuine end-of-pagination signal), whereas [Failed] means the
 * callable itself errored — a transient failure that must NOT be conflated with
 * "no more messages", so the caller can offer a retry instead of permanently
 * ending pagination.
 */
sealed interface ChannelOlderResult {
    data class Loaded(val page: ChannelMessagesPage) : ChannelOlderResult

    data object Failed : ChannelOlderResult
}

/** Pure code → send-error mapping. Branch on the HttpsError code, never the message. */
object ChannelErrorMapper {
    fun mapSend(code: ChannelErrorCode): ChannelSendError =
        when (code) {
            ChannelErrorCode.Unauthenticated -> ChannelSendError.SignedOut
            ChannelErrorCode.PermissionDenied -> ChannelSendError.NotMember
            ChannelErrorCode.InvalidArgument -> ChannelSendError.Invalid
            // profile-missing / still-invited convoy member / convoy-not-found all
            // collapse to a single neutral message (never reveal which).
            ChannelErrorCode.FailedPrecondition, ChannelErrorCode.NotFound ->
                ChannelSendError.CannotDeliver
            ChannelErrorCode.Other -> ChannelSendError.Generic
        }
}

/** Pure message-thread helpers (merge of the live window with paged older messages). */
object ChannelThread {
    /** Whether a draft is within 1..[CHANNEL_MESSAGE_MAX_LENGTH] after trimming. */
    fun isSendable(text: String): Boolean = text.trim().length in 1..CHANNEL_MESSAGE_MAX_LENGTH

    /**
     * Merges the live newest-window with accumulated older pages into a single
     * chronological (oldest-first) list, de-duplicated by id. Later duplicates
     * win, so a message that appears in both the live window and an older page
     * keeps its live copy.
     */
    fun merge(older: List<ChannelMessage>, live: List<ChannelMessage>): List<ChannelMessage> {
        val byId = LinkedHashMap<String, ChannelMessage>(older.size + live.size)
        for (m in older) byId[m.id] = m
        for (m in live) byId[m.id] = m
        return byId.values.sortedWith(
            compareBy({ it.createdAtMillis ?: Long.MAX_VALUE }, { it.id }),
        )
    }

    /** The pagination cursor for the next older page: the earliest message's ISO createdAt. */
    fun oldestCursor(messages: List<ChannelMessage>): String? =
        messages.minByOrNull { it.createdAtMillis ?: Long.MAX_VALUE }?.createdAtIso

    /**
     * True when the newest message is unread for the caller: it exists, was NOT
     * sent by the caller, and is newer than the caller's [lastReadAtMillis]
     * marker (null marker = never read → any message from someone else is
     * unread). Drives the community unread dot without a fan-out counter.
     */
    fun hasUnread(
        newest: ChannelMessage?,
        callerUid: String,
        lastReadAtMillis: Long?,
    ): Boolean {
        if (newest == null) return false
        if (newest.senderUid == callerUid) return false
        val createdAt = newest.createdAtMillis ?: return false
        return lastReadAtMillis == null || createdAt > lastReadAtMillis
    }
}

/**
 * Pure parsing of the `*-post` / `*-list` callable response payloads (plain
 * `Map`/`List` as the Firebase Functions SDK deserializes JSON). Missing/blank
 * required fields drop the row rather than crash, so a partial backend response
 * degrades gracefully. Callable responses carry ISO-8601 timestamp strings (the
 * live Firestore listeners, which carry Firebase `Timestamp`s, are parsed in the
 * Firebase repositories).
 */
object ChannelResponseParser {
    /** Maps a `*-post` success payload. A missing messageId fails the send. */
    fun parsePostSuccess(data: Map<String, Any?>?): ChannelSendResult {
        val messageId = (data?.get("messageId") as? String)?.takeIf { it.isNotBlank() }
        return if (messageId != null) {
            ChannelSendResult.Sent(messageId)
        } else {
            ChannelSendResult.Failed(ChannelSendError.Generic)
        }
    }

    /** Maps a `*-list` success payload into an older-page. */
    fun parseMessagesPage(data: Map<String, Any?>?): ChannelMessagesPage {
        val rawMessages = (data?.get("messages") as? List<*>).orEmptyList()
        val messages = rawMessages.mapNotNull { parseMessage(it) }
        val nextBefore = (data?.get("nextBefore") as? String)?.takeIf { it.isNotBlank() }
        val hasMore = data?.get("hasMore") as? Boolean ?: false
        return ChannelMessagesPage(messages = messages, nextBefore = nextBefore, hasMore = hasMore)
    }

    /** The `lastReadAt` marker from a `communityChat-list` payload (or null). */
    fun parseLastReadAt(data: Map<String, Any?>?): String? =
        (data?.get("lastReadAt") as? String)?.takeIf { it.isNotBlank() }

    /** Parses one message row from a callable payload (ISO createdAt). */
    fun parseMessage(raw: Any?): ChannelMessage? {
        val map = raw as? Map<*, *> ?: return null
        val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val senderUid = (map["senderUid"] as? String) ?: return null
        val iso = map["createdAt"] as? String
        return ChannelMessage(
            id = id,
            senderUid = senderUid,
            text = map["text"] as? String ?: "",
            senderDisplayName = map["senderDisplayName"] as? String,
            senderAvatarPath = map["senderAvatarPath"] as? String,
            createdAtMillis = iso?.let(::channelIsoToMillisOrNull),
            createdAtIso = iso,
        )
    }

    private fun List<*>?.orEmptyList(): List<*> = this ?: emptyList<Any?>()
}

/**
 * Best-effort ISO-8601 → epoch-millis for callable message rows (used only for
 * chronological ordering; a parse failure just sorts the message last). Pure (no
 * Android types); `java.time` is available on the app's minSdk (26).
 */
fun channelIsoToMillisOrNull(iso: String): Long? =
    try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
