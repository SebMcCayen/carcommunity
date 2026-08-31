import Foundation

/// Per-convoy chat access — the iOS port of Android's `ConvoyChatRepository`.
/// The convoy list comes from the member-gated `convoy-list` callable (projected
/// client-side to ACCEPTED-member convoys, the only ones whose chat the caller
/// may read/post — ``ConvoyChatMapper``). Messages read from a live Firestore
/// listener on `convoyChats/{convoyId}/messages` (rules grant reads only to
/// accepted members), block-filtered client-side; sending, older-page
/// pagination, mark-read, and reporting go through the `convoyChat-*` /
/// `chatchannels-*` callables (europe-west1). Firebase-free so it is testable
/// with fakes.
protocol ConvoyChatRepository: AnyObject, Sendable {
    /// `convoy-list` — the caller's ACCEPTED-member convoys (chat-eligible),
    /// newest-first. A one-shot load (not a listener), so it carries an explicit
    /// retryable ``ConvoyListState/error``. Android: `listConvoys`.
    func listConvoys() async -> ConvoyListState

    /// Live newest-window of `convoyId`'s channel, already block-filtered and
    /// chronological. Android: `observeMessages`.
    func observeMessages(convoyId: String) -> AsyncStream<ChannelMessagesState>

    /// An older page of `convoyId` before the `before` ISO cursor
    /// (`convoyChat-list`). Android: `loadOlder`.
    func loadOlder(convoyId: String, before: String) async -> ChannelOlderResult

    /// `convoyChat-post` — posts `text` to `convoyId`'s channel. `clientId` is
    /// the optimistic idempotency key; `replyToMessageId` is sent only when the
    /// `chatReplies` flag is on. Convoy chat accepts no @mentions. Android:
    /// `post`.
    func post(
        convoyId: String,
        text: String,
        clientId: String?,
        replyToMessageId: String?
    ) async -> ChannelSendResult

    /// `convoyChat-markRead` — stamps the caller's per-convoy last-read marker;
    /// best-effort. Android: `markRead`.
    func markRead(convoyId: String) async

    /// `chatchannels-reportMessage` with `channel: "convoy"` and this `convoyId`.
    /// Android reports via the shared community-repo method; iOS exposes it on
    /// the convoy repo so ``ConvoyChatScreen`` stays self-contained.
    func report(convoyId: String, messageId: String, reason: ChatReportReason) async -> ChannelReportResult

    /// The signed-in user's uid, or nil with no session.
    func currentUserId() -> String?
}
