package com.kungsbackacarcommunity.app.dm

/**
 * Direct-messaging domain (1:1 friend DMs). The backend (europe-west1 callables
 * `dm-sendMessage` / `dm-getMessages` / `dm-markRead`, plus member-readable
 * Firestore `conversations/{pairId}` + `.../messages`) is the source of truth;
 * the client never writes the tree. Everything here is pure Kotlin so the
 * mapping/parsing/merge logic is JVM-unit-testable without Firebase.
 *
 * Contract highlights ([functions/src/dm]):
 *  - `pairId` = the two participant UIDs sorted and joined with `__`
 *    ([dmPairId]) — order-independent, so both friends resolve the SAME
 *    canonical document. This means the client can derive the conversation id
 *    for any friend locally, without a lookup.
 *  - conversation docs carry denormalized `memberProfiles`, a per-member
 *    `unread` map, and a `lastMessage` preview, so the live inbox listener
 *    renders fully client-side.
 *  - messages page newest-first, 30 per page; `dm-getMessages` takes an ISO
 *    `before` cursor for older pages.
 */

/** Backend DM_MESSAGE_MAX_LENGTH (DMs get 2000, vs event chat's 1000). */
const val DM_MESSAGE_MAX_LENGTH = 2000

/** Backend DM_MESSAGES_PAGE_SIZE (newest-first window). */
const val DM_MESSAGES_PAGE_SIZE = 30

/** A conversation participant, as surfaced to the caller's UI. */
data class DmUser(
    val uid: String,
    val displayName: String?,
    val avatarPath: String?,
)

/** A single rendered DM. [createdAtIso] is the pagination cursor for older pages. */
data class DmMessage(
    val id: String,
    val senderUid: String,
    val text: String,
    val createdAtMillis: Long?,
    val createdAtIso: String?,
)

/** Denormalized last-message preview shown on an inbox row. */
data class DmMessagePreview(
    val text: String,
    val senderUid: String,
    val createdAtMillis: Long?,
)

/** One inbox row: the other participant, the last-message preview, and my unread count. */
data class DmConversation(
    val conversationId: String,
    val otherUser: DmUser,
    val lastMessage: DmMessagePreview?,
    val unreadCount: Int,
    val lastMessageAtMillis: Long?,
)

/**
 * A raw conversation document, with Firebase types already extracted to plain
 * Kotlin (timestamps → epoch millis) by the Firebase repository. Kept separate
 * from [DmConversation] so the caller-oriented projection ([DmMapper.conversation])
 * stays a pure, testable function.
 */
data class DmConversationDoc(
    val members: List<String>,
    val memberProfiles: Map<String, DmUser>,
    val lastMessageText: String?,
    val lastMessageSenderUid: String?,
    val lastMessageAtMillis: Long?,
    val unread: Map<String, Long>,
)

/**
 * The canonical HttpsError codes we branch on, decoupled from Firebase's
 * `FirebaseFunctionsException.Code` so the mapping is testable on a plain JVM.
 * Any code we don't special-case collapses to [Other].
 */
enum class DmErrorCode {
    Unauthenticated,
    PermissionDenied,
    InvalidArgument,
    FailedPrecondition,
    NotFound,
    Other,
}

/**
 * A user-facing send failure category. [CannotDeliver] is deliberately neutral:
 * the backend returns `failed-precondition` for BOTH "not friends" and "blocked"
 * with only differing messages (never a discriminator), and privacy parity
 * forbids revealing a block — so both collapse to one neutral message.
 */
enum class DmSendError {
    SignedOut,
    NotMember,
    Invalid,
    CannotDeliver,
    Generic,
}

/** Outcome of `dm-sendMessage`. */
sealed interface DmSendResult {
    data class Sent(val conversationId: String, val messageId: String) : DmSendResult

    data class Failed(val error: DmSendError) : DmSendResult
}

/** A page of older messages from `dm-getMessages` (newest-first within the page). */
data class DmMessagesPage(
    val messages: List<DmMessage>,
    val nextBefore: String?,
    val hasMore: Boolean,
)

/**
 * Outcome of an older-page load ([DmRepository.loadOlder]). A [Loaded] page
 * carries the backend's own `hasMore` (a genuine end-of-pagination signal),
 * whereas [Failed] means the callable itself errored — a transient failure that
 * must NOT be conflated with "no more messages", so the caller can offer a
 * retry instead of permanently ending pagination.
 */
sealed interface DmOlderResult {
    data class Loaded(val page: DmMessagesPage) : DmOlderResult

    data object Failed : DmOlderResult
}

/**
 * Canonical, order-independent conversation id for a pair of users: the two
 * UIDs sorted lexicographically and joined with `__`. Mirrors the backend
 * `dmPairId`, so `dmPairId(a, b) == dmPairId(b, a)` and the client can resolve
 * any friend's conversation id locally.
 */
fun dmPairId(a: String, b: String): String = listOf(a, b).sorted().joinToString("__")

/** Pure code → send-error mapping. Branch on the HttpsError code, never the message. */
object DmErrorMapper {
    fun mapSend(code: DmErrorCode): DmSendError =
        when (code) {
            DmErrorCode.Unauthenticated -> DmSendError.SignedOut
            DmErrorCode.PermissionDenied -> DmSendError.NotMember
            DmErrorCode.InvalidArgument -> DmSendError.Invalid
            // Both NOT_FRIENDS and NOT_DELIVERABLE (blocked) arrive as
            // failed-precondition with no discriminator; collapse to a single
            // neutral message so a block is never revealed.
            DmErrorCode.FailedPrecondition -> DmSendError.CannotDeliver
            DmErrorCode.NotFound, DmErrorCode.Other -> DmSendError.Generic
        }
}

/** Pure projection of conversation/message docs into caller-oriented models. */
object DmMapper {
    /** The other participant's uid (the first member that isn't the caller). */
    fun otherMember(members: List<String>, callerUid: String): String? =
        members.firstOrNull { it != callerUid }

    /** The caller's own unread count, clamped to a non-negative Int. */
    fun unreadFor(unread: Map<String, Long>, callerUid: String): Int {
        val raw = unread[callerUid] ?: 0L
        return if (raw > 0L) raw.toInt() else 0
    }

    /**
     * Projects a raw [DmConversationDoc] into the caller's inbox row: the OTHER
     * member's denormalized profile, the caller's own unread count, and the
     * last-message preview. A conversation whose members don't include a
     * resolvable other user still yields a row keyed by the derived uid (empty).
     */
    fun conversation(
        conversationId: String,
        doc: DmConversationDoc,
        callerUid: String,
    ): DmConversation {
        val otherUid = otherMember(doc.members, callerUid) ?: ""
        val otherProfile = doc.memberProfiles[otherUid] ?: DmUser(otherUid, null, null)
        val preview =
            doc.lastMessageSenderUid?.let { sender ->
                DmMessagePreview(
                    text = doc.lastMessageText ?: "",
                    senderUid = sender,
                    createdAtMillis = doc.lastMessageAtMillis,
                )
            }
        return DmConversation(
            conversationId = conversationId,
            otherUser = DmUser(otherUid, otherProfile.displayName, otherProfile.avatarPath),
            lastMessage = preview,
            unreadCount = unreadFor(doc.unread, callerUid),
            lastMessageAtMillis = doc.lastMessageAtMillis,
        )
    }

    /** Sorts inbox rows newest-first (client-side, so no composite index is needed). */
    fun sortConversations(conversations: List<DmConversation>): List<DmConversation> =
        conversations.sortedByDescending { it.lastMessageAtMillis ?: Long.MIN_VALUE }
}

/** Pure message-thread helpers (merge of the live window with paged older messages). */
object DmThread {
    /** Whether a draft is within 1..[DM_MESSAGE_MAX_LENGTH] after trimming. */
    fun isSendable(text: String): Boolean = text.trim().length in 1..DM_MESSAGE_MAX_LENGTH

    /**
     * Merges the live newest-window with accumulated older pages into a single
     * chronological (oldest-first) list, de-duplicated by id. Later duplicates
     * win, so a message that appears in both the live window and an older page
     * keeps its live copy.
     */
    fun merge(older: List<DmMessage>, live: List<DmMessage>): List<DmMessage> {
        val byId = LinkedHashMap<String, DmMessage>(older.size + live.size)
        for (m in older) byId[m.id] = m
        for (m in live) byId[m.id] = m
        return byId.values.sortedWith(
            compareBy({ it.createdAtMillis ?: Long.MAX_VALUE }, { it.id }),
        )
    }

    /** The pagination cursor for the next older page: the earliest message's ISO createdAt. */
    fun oldestCursor(messages: List<DmMessage>): String? =
        messages.minByOrNull { it.createdAtMillis ?: Long.MAX_VALUE }?.createdAtIso
}

/**
 * Pure parsing of the `dm-*` callable response payloads (plain `Map`/`List` as
 * the Firebase Functions SDK deserializes JSON). Missing/blank required fields
 * drop the row rather than crash, so a partial backend response degrades
 * gracefully. Callable responses carry ISO-8601 timestamp strings (the live
 * Firestore listeners, which carry Firebase `Timestamp`s, are parsed in the
 * Firebase repository).
 */
object DmResponseParser {
    /** Maps a `dm-sendMessage` success payload. Missing ids fail the send. */
    fun parseSendSuccess(data: Map<String, Any?>?): DmSendResult {
        val conversationId = (data?.get("conversationId") as? String)?.takeIf { it.isNotBlank() }
        val messageId = (data?.get("messageId") as? String)?.takeIf { it.isNotBlank() }
        return if (conversationId != null && messageId != null) {
            DmSendResult.Sent(conversationId, messageId)
        } else {
            DmSendResult.Failed(DmSendError.Generic)
        }
    }

    /** Maps a `dm-getMessages` success payload into an older-page. */
    fun parseMessagesPage(data: Map<String, Any?>?): DmMessagesPage {
        val rawMessages = (data?.get("messages") as? List<*>).orEmpty()
        val messages = rawMessages.mapNotNull { parseMessage(it) }
        val nextBefore = (data?.get("nextBefore") as? String)?.takeIf { it.isNotBlank() }
        val hasMore = data?.get("hasMore") as? Boolean ?: false
        return DmMessagesPage(messages = messages, nextBefore = nextBefore, hasMore = hasMore)
    }

    /** Parses one message row from a callable payload (ISO createdAt). */
    fun parseMessage(raw: Any?): DmMessage? {
        val map = raw as? Map<*, *> ?: return null
        val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val senderUid = (map["senderUid"] as? String) ?: return null
        val iso = map["createdAt"] as? String
        return DmMessage(
            id = id,
            senderUid = senderUid,
            text = map["text"] as? String ?: "",
            createdAtMillis = iso?.let(::isoToMillisOrNull),
            createdAtIso = iso,
        )
    }

    private fun List<*>?.orEmpty(): List<*> = this ?: emptyList<Any?>()
}

/**
 * Best-effort ISO-8601 → epoch-millis for callable message rows (used only for
 * chronological ordering; a parse failure just sorts the message last). Pure
 * (no Android types); `java.time` is available on the app's minSdk (26).
 */
fun isoToMillisOrNull(iso: String): Long? =
    try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }

/** Epoch-millis → ISO-8601 (UTC, `Z`) — the cursor format `dm-getMessages` expects. */
fun millisToIso(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()
