package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.chat.ChatReportReason
import kotlinx.coroutines.flow.Flow

/**
 * Community (app-wide) chat access. Reads are a live Firestore listener on
 * `communityChat/global/messages` (rules grant any active member the read);
 * sending, older-page pagination, and mark-read go through the member-gated
 * `communityChat-*` callables (europe-west1). Firebase-free interface so the
 * coordinator/screens are unit- and UI-testable with fakes (mirrors DmRepository).
 *
 * Unread is a lightweight per-user LAST-READ marker
 * (`userPrivate/{uid}.communityChatLastReadAt`, owner-only readable) rather than a
 * fan-out counter: [observeUnread] combines the newest-message listener with the
 * marker so the caller sees a dot when the newest message post-dates their marker.
 */
interface CommunityChatRepository {
    /** Live newest-window of the community channel, chronological. */
    fun observeMessages(): Flow<ChannelMessagesState>

    /**
     * Live "has unread" flag for [uid]: true while the newest message is from
     * someone else and newer than the caller's last-read marker. Emits false
     * once [markRead] runs (or while the channel is empty).
     */
    fun observeUnread(uid: String): Flow<Boolean>

    /**
     * `communityChat-post` — posts [text] to the global channel, @mentioning
     * [mentionedUids] (at most [MAX_MESSAGE_MENTIONS] — the server REJECTS more
     * with `invalid-argument`). The uids come from the composer's @-picker, never
     * from parsing names out of [text]: display names are not unique, so a parsed
     * mention would sooner or later notify the wrong member. The returned
     * [ChannelSendResult.Sent] echoes the ACCEPTED subset.
     *
     * [clientId] is the optimistic idempotency key: the backend uses it as the
     * message doc id, so a retry is exactly-once and the client reconciles its
     * bubble by matching it. Null falls back to a server auto-id (legacy) doc.
     */
    suspend fun post(
        text: String,
        mentionedUids: List<String> = emptyList(),
        clientId: String? = null,
    ): ChannelSendResult

    /**
     * `communityChat-post` with an inline reply target. [replyToMessageId] is the
     * id of the message being replied to (WhatsApp-style quote, not a thread): the
     * client sends only the id and the server snapshots the parent from THIS
     * channel itself (ignored while the `chatReplies` flag is off). Null is an
     * ordinary, non-reply message.
     *
     * Defaults to delegating to the 3-arg overload, so a fake predating replies
     * keeps working (it drops the reply target); the Firebase repository overrides
     * this to forward the id.
     */
    suspend fun post(
        text: String,
        mentionedUids: List<String>,
        clientId: String?,
        replyToMessageId: String?,
    ): ChannelSendResult = post(text, mentionedUids, clientId)

    /** `communityChat-list` — an older page before the [before] ISO cursor. */
    suspend fun loadOlder(before: String): ChannelOlderResult

    /** `communityChat-markRead` — stamps the caller's last-read marker. Idempotent. */
    suspend fun markRead()

    /**
     * `chatchannels-reportMessage` (channel: 'community') — files a moderation
     * report against the message [messageId] with [reason]. Eligibility mirrors the
     * channel read rule (any active member), reporting your OWN message is rejected
     * server-side (the client also gates this on [MessageModeration.canActOn]), and
     * the message is snapshotted into the report so a later TTL-delete can't blank
     * it. The reported message id is a community-channel doc id; the channel scope
     * ('global') is fixed server-side, so the client sends only the reason + id.
     *
     * Binary [ChannelReportResult] by design: the queue is fire-and-forget and the
     * backend never reveals whether a prior report existed, so the reporter only
     * learns "reached the backend" vs "didn't".
     *
     * Defaults to [ChannelReportResult.Failed] so a fake predating reporting fails
     * closed rather than falsely reporting success; the Firebase repository overrides it.
     */
    suspend fun report(messageId: String, reason: ChatReportReason): ChannelReportResult =
        ChannelReportResult.Failed
}
