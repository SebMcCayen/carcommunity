package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.profile.LiveProfile
import com.kungsbackacarcommunity.app.profile.LiveProfiles

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
 * Delivery state of a rendered channel message. Server-sourced messages (the live
 * listener, paginated pages) are always [Sent]. Only the caller's own OPTIMISTIC
 * bubble — shown instantly on tap before the `*-post` round-trip resolves —
 * carries [Sending] or [Failed]; it is reconciled away (by [ChannelMessage.clientId],
 * which the backend stores as the delivered doc id) the moment the real document
 * arrives from the listener. Mirrors dm/DmDeliveryState.
 */
enum class ChannelDeliveryState {
    Sent,
    Sending,
    Failed,
}

/**
 * One rendered channel message. [createdAtIso] is the pagination cursor for
 * older pages (the `before` argument the `*-list` callables expect). The
 * denormalized sender profile lets the channel render the author's name/avatar
 * without a lookup.
 *
 * [mentionedUids] is the server-ACCEPTED @mention set (see ChannelMentions.kt):
 * always present, `[]` for a message with no mentions, for every pre-mentions
 * message, and for every convoy message (convoyChat-post accepts no mentions).
 * It carries uids and NO offsets — the server never parsed the text — so
 * highlighting maps uids back onto spans client-side via [MentionRendering].
 *
 * [clientId] is the sender's optimistic idempotency key (see the DM domain's
 * DmMessage.clientId for the full rationale). It is used VERBATIM as the message
 * doc [id] by the backend, so a delivered message carries the key its sender
 * used; locally it is only the join key that reconciles the caller's OWN pending
 * bubble against the arriving snapshot ([ChannelThread.mergeWithPending]). It is
 * NOT an identity/authorization signal — use [senderUid] for that. Null on legacy
 * messages sent without a key.
 */
data class ChannelMessage(
    val id: String,
    val senderUid: String,
    val text: String,
    val senderDisplayName: String?,
    val senderAvatarPath: String?,
    val createdAtMillis: Long?,
    val createdAtIso: String?,
    val mentionedUids: List<String> = emptyList(),
    val clientId: String? = null,
    val deliveryState: ChannelDeliveryState = ChannelDeliveryState.Sent,
    /**
     * Why an optimistic send failed, set only on a [ChannelDeliveryState.Failed]
     * bubble. Drives the specific failure reason shown under the bubble and
     * whether a retry is offered ([ChannelSendError.isRetryable]). Null otherwise.
     */
    val sendError: ChannelSendError? = null,
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

/**
 * Whether re-sending the SAME message could plausibly succeed. Only [Generic]
 * (a transient/network/unknown failure) is retryable; the rest are terminal for
 * this message — signed out, not an active member, invalid input, or
 * cannot-deliver — so a failed bubble shows the reason WITHOUT a pointless "tap
 * to retry" that would just fail the same way. Mirrors dm/DmSendError.isRetryable.
 */
val ChannelSendError.isRetryable: Boolean
    get() = this == ChannelSendError.Generic

/** Outcome of a `*-post` callable. */
sealed interface ChannelSendResult {
    /**
     * [mentionedUids] is the ACCEPTED mention set the server echoes back, which
     * may be SMALLER than what was sent: everything past the <= 10 cap is a
     * silent drop (a member who deleted their account, lost their subscription,
     * or blocked the sender between picking and posting), never a throw. The
     * composer reconciles against this rather than assuming its picks landed.
     */
    data class Sent(
        val messageId: String,
        val mentionedUids: List<String> = emptyList(),
    ) : ChannelSendResult

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

    /**
     * Replaces each message's DENORMALIZED sender profile with that sender's
     * current one, where a live profile was loaded.
     *
     * `senderDisplayName`/`senderAvatarPath` are stamped onto every message at
     * post time and never rewritten, so without this a member who changes their
     * avatar keeps the old one on every message they have ever posted. The
     * product decision is that a channel shows a member as they are NOW, matching
     * their profile screen and the friends list — the denormalization exists to
     * spare the render a per-message profile lookup, not to pin identity to post
     * time.
     *
     * De-duplication happens in the caller's [LiveProfiles.uidsOf] pass, so the
     * cost is one profile read per distinct SENDER in the window, never one per
     * message. Message ids, text and ordering keys are untouched, so hydration
     * cannot reorder the thread or disturb [merge]/[mergeWithPending].
     */
    fun hydrate(
        messages: List<ChannelMessage>,
        live: Map<String, LiveProfile>,
    ): List<ChannelMessage> {
        if (live.isEmpty()) return messages
        return messages.map { message ->
            val resolved =
                LiveProfiles.resolve(
                    message.senderUid,
                    LiveProfile(message.senderDisplayName, message.senderAvatarPath),
                    live,
                )
            message.copy(
                senderDisplayName = resolved.displayName,
                senderAvatarPath = resolved.avatarPath,
            )
        }
    }

    /** The pagination cursor for the next older page: the earliest message's ISO createdAt. */
    fun oldestCursor(messages: List<ChannelMessage>): String? =
        messages.minByOrNull { it.createdAtMillis ?: Long.MAX_VALUE }?.createdAtIso

    /**
     * Merges the server-sourced messages ([merge] of older + live) with the
     * caller's still-[pending] optimistic bubbles for display. A pending bubble
     * whose id (its clientId) has ALREADY arrived in the server set is dropped:
     * the delivered document — whose doc id equals that clientId — supersedes it,
     * so an optimistic send and its snapshot render as exactly ONE message, never
     * two. The optimistic bubble's local timestamp slots it in the same (newest)
     * position the real doc will take, so it doesn't jump on reconcile. Mirrors
     * dm/DmThread.mergeWithPending.
     */
    fun mergeWithPending(
        older: List<ChannelMessage>,
        live: List<ChannelMessage>,
        pending: List<ChannelMessage>,
    ): List<ChannelMessage> {
        val real = merge(older, live)
        if (pending.isEmpty()) return real
        val realIds = real.mapTo(HashSet(real.size)) { it.id }
        val stillPending = pending.filter { it.id !in realIds }
        if (stillPending.isEmpty()) return real
        return (real + stillPending).sortedWith(
            compareBy({ it.createdAtMillis ?: Long.MAX_VALUE }, { it.id }),
        )
    }

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

    /**
     * How many of [window] are unread for the caller — the COUNTING form of
     * [hasUnread], for the surfaces that show a number rather than a dot (the
     * convoy bar's chat badge). The per-message rule is identical: a message is
     * unread when someone else sent it and it post-dates the caller's
     * [lastReadAtMillis] marker (a null marker = never opened, so everything from
     * someone else counts).
     *
     * The result is bounded by [window], deliberately: the window is the caller's
     * ALREADY-subscribed newest-message listener, not a query of its own, so the
     * count costs nothing beyond what the channel is syncing anyway. That makes it
     * a FLOOR on a very busy channel — which is exactly what a capped badge ("9+")
     * needs, since a window sized above the display cap renders a saturated count
     * and the true count identically.
     *
     * A message with no parseable `createdAt` is not counted, matching [hasUnread]:
     * with no instant to compare there is nothing saying it is newer than the
     * marker, and guessing "unread" would light a badge that opening cannot clear.
     */
    fun unreadCount(
        window: List<ChannelMessage>,
        callerUid: String,
        lastReadAtMillis: Long?,
    ): Int = window.count { message ->
        val createdAt = message.createdAtMillis
        message.senderUid != callerUid &&
            createdAt != null &&
            (lastReadAtMillis == null || createdAt > lastReadAtMillis)
    }

    /**
     * True when ANY convoy has an unread message: some convoy whose newest
     * DELIVERED-message time ([latestByConvoy], maintained server-side by the
     * convoyChat.post fan-out) is later than the caller's last-read marker for that
     * same convoy ([lastReadByConvoy]), or that has no marker at all (never opened,
     * so any delivered message counts). The AGGREGATE form of [hasUnread] across
     * convoys — but derived from two owner-only `userPrivate` maps rather than a
     * per-convoy message listener, so the Convoys tab dot and the map-shell dot
     * cost ONE document listener regardless of how many convoys the caller is in.
     *
     * Both maps hold epoch millis keyed by convoy id. A convoy present only in
     * [lastReadByConvoy] (read, nothing new since) never contributes; a convoy the
     * post fan-out has not stamped for this caller is simply absent from
     * [latestByConvoy]. Pure, so the rule is unit-testable off-device.
     */
    fun anyConvoyUnread(
        latestByConvoy: Map<String, Long>,
        lastReadByConvoy: Map<String, Long>,
    ): Boolean = latestByConvoy.any { (convoyId, latestMillis) ->
        val lastReadMillis = lastReadByConvoy[convoyId]
        lastReadMillis == null || latestMillis > lastReadMillis
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
    /**
     * Maps a `*-post` success payload. A missing messageId fails the send. A
     * missing/malformed `mentionedUids` parses as the empty accepted set — the
     * message itself landed, so a mention-echo we can't read must not fail it
     * (convoy-post never echoes one at all).
     */
    fun parsePostSuccess(data: Map<String, Any?>?): ChannelSendResult {
        val messageId = (data?.get("messageId") as? String)?.takeIf { it.isNotBlank() }
        return if (messageId != null) {
            ChannelSendResult.Sent(messageId, parseMentionedUids(data["mentionedUids"]))
        } else {
            ChannelSendResult.Failed(ChannelSendError.Generic)
        }
    }

    /** `mentionedUids` from a payload/doc: non-blank strings only, deduplicated. */
    fun parseMentionedUids(raw: Any?): List<String> =
        (raw as? List<*>)
            ?.mapNotNull { (it as? String)?.takeIf { uid -> uid.isNotBlank() } }
            ?.distinct()
            .orEmpty()

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
        // senderUid is required (author identity + own/other + unread logic); a
        // missing OR blank value is malformed, so drop the row like a missing id.
        val senderUid = (map["senderUid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val iso = map["createdAt"] as? String
        return ChannelMessage(
            id = id,
            senderUid = senderUid,
            text = map["text"] as? String ?: "",
            senderDisplayName = map["senderDisplayName"] as? String,
            senderAvatarPath = map["senderAvatarPath"] as? String,
            createdAtMillis = iso?.let(::channelIsoToMillisOrNull),
            createdAtIso = iso,
            mentionedUids = parseMentionedUids(map["mentionedUids"]),
            clientId = (map["clientId"] as? String)?.takeIf { it.isNotBlank() },
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
