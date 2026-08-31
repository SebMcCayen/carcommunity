import Foundation

/// The single APP-WIDE community channel's read/write access — the iOS port of
/// Android's `CommunityChatRepository`. Firebase-free so the coordinator and
/// screens are unit-testable with fakes (mirrors ``EventsRepository``).
///
/// Reads are a direct live Firestore snapshot listener on
/// `communityChat/global/messages` (member-readable per
/// firebase/firestore.rules), bounded to ``channelMessagesPageSize`` newest-first
/// and filtered CLIENT-side against the caller's mutual-hidden set
/// (``ChatBlockVisibility``) — a Firestore rule cannot filter a list query
/// per-document. Sending, older-page pagination, mark-read, and reporting go
/// through the member-gated `communityChat-*` / `chatchannels-*` callables
/// (europe-west1); the client never writes the message tree.
protocol CommunityChatRepository: AnyObject, Sendable {
    /// Live newest-window of the community channel, already block-filtered and
    /// chronological (oldest-first). Each call returns a fresh stream backed by
    /// its own listeners; terminating the stream detaches them. Emits SETTLED
    /// states only — the coordinator supplies ``ChannelMessagesState/loading``
    /// before the first emission. Android: `observeMessages`.
    func observeMessages() -> AsyncStream<ChannelMessagesState>

    /// An older page before the `before` ISO-8601 cursor (`communityChat-list`,
    /// page size 30). Android: `loadOlder`.
    func loadOlder(before: String) async -> ChannelOlderResult

    /// `communityChat-post` — posts `text` with the accepted @mention set.
    /// `clientId` is the optimistic idempotency key (used verbatim as the doc
    /// id). `replyToMessageId` is sent only when the `chatReplies` flag is on
    /// (the backend ignores it otherwise). Android: `post`.
    func post(
        text: String,
        mentionedUids: [String],
        clientId: String?,
        replyToMessageId: String?
    ) async -> ChannelSendResult

    /// `communityChat-markRead` — best-effort; a transient failure is swallowed.
    /// Android: `markRead`.
    func markRead() async

    /// `chatchannels-reportMessage` with `channel: "community"`. Android:
    /// `report`.
    func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult

    /// The signed-in user's uid, or nil with no session — the Firebase seam owns
    /// identity so coordinators stay Firebase-free (as in ``EventsRepository``).
    func currentUserId() -> String?
}
