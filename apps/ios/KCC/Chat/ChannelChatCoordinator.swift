import Foundation
import Observation

/// The channel-agnostic surface a ``ChannelChatCoordinator`` drives — one bound
/// channel (the community channel, or one convoy's channel). Both
/// ``CommunityChatRepository`` and ``ConvoyChatRepository`` are adapted to this
/// so the SAME coordinator and screen render both, mirroring Android's single
/// `ChannelChatCoordinator` used by both `CommunityChannelRoute` and
/// `ConvoyChannelRoute`.
protocol ChannelChatSource: Sendable {
    func observeMessages() -> AsyncStream<ChannelMessagesState>
    func loadOlder(before: String) async -> ChannelOlderResult
    /// Community forwards the accepted @mention set; convoy always sends none.
    func post(text: String, clientId: String?, replyToMessageId: String?) async -> ChannelSendResult
    func markRead() async
    func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult
    func currentUserId() -> String?
}

/// Adapts a ``CommunityChatRepository`` to ``ChannelChatSource``. The @-mention
/// picker is a later slice, so posts carry an empty accepted set for now (a
/// delivered message's ``ChannelMessage/mentionedUids`` still decodes; there is
/// no UI highlighting of it yet).
struct CommunityChatSource: ChannelChatSource {
    let repository: CommunityChatRepository

    func observeMessages() -> AsyncStream<ChannelMessagesState> { repository.observeMessages() }
    func loadOlder(before: String) async -> ChannelOlderResult { await repository.loadOlder(before: before) }
    func post(text: String, clientId: String?, replyToMessageId: String?) async -> ChannelSendResult {
        await repository.post(text: text, mentionedUids: [], clientId: clientId, replyToMessageId: replyToMessageId)
    }
    func markRead() async { await repository.markRead() }
    func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult {
        await repository.report(messageId: messageId, reason: reason)
    }
    func currentUserId() -> String? { repository.currentUserId() }
}

/// Adapts one convoy's channel (a bound `convoyId`) to ``ChannelChatSource``.
struct ConvoyChatSource: ChannelChatSource {
    let repository: ConvoyChatRepository
    let convoyId: String

    func observeMessages() -> AsyncStream<ChannelMessagesState> { repository.observeMessages(convoyId: convoyId) }
    func loadOlder(before: String) async -> ChannelOlderResult {
        await repository.loadOlder(convoyId: convoyId, before: before)
    }
    func post(text: String, clientId: String?, replyToMessageId: String?) async -> ChannelSendResult {
        await repository.post(convoyId: convoyId, text: text, clientId: clientId, replyToMessageId: replyToMessageId)
    }
    func markRead() async { await repository.markRead(convoyId: convoyId) }
    func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult {
        await repository.report(convoyId: convoyId, messageId: messageId, reason: reason)
    }
    func currentUserId() -> String? { repository.currentUserId() }
}

/// The older-page pagination state of a channel thread.
enum ChannelOlderPaging: Equatable, Sendable {
    /// Idle — an older page may be requested.
    case idle
    /// A `*-list` call is in flight.
    case loading
    /// The last `*-list` call failed transiently — retryable, NOT end-of-list.
    case failed
    /// The backend reported no more messages before the oldest we hold.
    case exhausted
}

/// Orchestrates ONE channel thread (community or a convoy): folds the live
/// block-filtered window into a display list, drives optimistic sending with
/// idempotent client ids, older-page pagination, mark-read, reporting, and the
/// `chatReplies`-gated reply composer. Pure Swift (no Firebase/SwiftUI) so it is
/// unit-testable with a fake ``ChannelChatSource`` — the iOS counterpart of
/// Android's `ChannelChatCoordinator`.
@MainActor
@Observable
final class ChannelChatCoordinator {
    private let source: ChannelChatSource
    /// The caller's uid, captured once at init (the Firebase seam owns identity).
    /// Used to mark own vs other bubbles and to author optimistic bubbles.
    let currentUserId: String?
    /// Whether the `chatReplies` feature is on — gates the reply affordance and
    /// whether a `replyToMessageId` is sent. Default OFF.
    let chatRepliesEnabled: Bool

    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    /// The live block-filtered newest window (server-sourced), oldest-first.
    private var live: [ChannelMessage] = []
    /// Accumulated older pages (server-sourced), oldest-first.
    private var older: [ChannelMessage] = []
    /// The caller's own not-yet-reconciled optimistic bubbles.
    private var pending: [ChannelMessage] = []

    /// True until the first live emission arrives (the coordinator supplies the
    /// loading state, like ``EventsCoordinator``).
    private(set) var isInitialLoading = true
    /// The merged display list: older + live + still-pending bubbles,
    /// chronological. Recomputed on every change so the view is a dumb read.
    private(set) var messages: [ChannelMessage] = []
    private(set) var olderPaging: ChannelOlderPaging = .idle
    /// The message the composer is replying to, or nil. Only ever non-nil while
    /// ``chatRepliesEnabled``.
    private(set) var replyTarget: ChannelMessage?

    init(source: ChannelChatSource, chatRepliesEnabled: Bool = ChatFeatureFlags.chatRepliesDefault) {
        self.source = source
        self.currentUserId = source.currentUserId()
        self.chatRepliesEnabled = chatRepliesEnabled
    }

    deinit {
        subscription?.cancel()
    }

    /// Begins observing on first appearance. Idempotent — a second call keeps the
    /// live subscription and current state instead of flashing back to loading
    /// (matches ``EventsCoordinator/start()``). Also stamps the read marker.
    func start() {
        guard subscription == nil else { return }
        subscribe()
        Task { [source] in await source.markRead() }
    }

    /// Tears the listener down and re-subscribes from scratch (the retry
    /// affordance) — ``EventsCoordinator/reload()`` semantics. Also clears any
    /// active reply target: without this a stale reply banner could survive a
    /// reload and quote it onto the next send.
    func reload() {
        older = []
        pending = []
        olderPaging = .idle
        clearReply()
        subscribe()
    }

    private func subscribe() {
        subscription?.cancel()
        isInitialLoading = true
        live = []
        recompute()
        // The stream is created HERE (synchronously), so the listener attaches
        // by the time reload() returns — deterministic and testable, exactly
        // like EventsCoordinator.
        let stream = source.observeMessages()
        subscription = Task { [weak self] in
            for await state in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(state)
            }
        }
    }

    private func apply(_ state: ChannelMessagesState) {
        switch state {
        case .loading:
            break
        case .loaded(let messages):
            isInitialLoading = false
            live = messages
            recompute()
            // A fresh window means possibly-new messages — refresh the marker.
            Task { [source] in await source.markRead() }
        }
    }

    /// Merges live + older + pending into the display list. A pending bubble
    /// whose delivered document has arrived in the server set is dropped
    /// (``ChannelThread/mergeWithPending``), so an optimistic send renders as
    /// exactly one message.
    private func recompute() {
        messages = ChannelThread.mergeWithPending(older: older, live: live, pending: pending)
    }

    // MARK: - Sending

    /// Sends `text` optimistically: an immediate ``ChannelDeliveryState/sending``
    /// bubble, then the `*-post` round-trip. On success the live listener
    /// delivers the real document (whose id equals the bubble's clientId) and the
    /// bubble is reconciled away; on failure the bubble flips to
    /// ``ChannelDeliveryState/failed`` carrying the reason. A draft outside
    /// 1...``channelMessageMaxLength`` is ignored, and so is a signed-out caller
    /// (posting would fail server-side anyway, and there is no uid to author an
    /// optimistic bubble with). Returns the clientId used, or nil when nothing
    /// was sent (for tests/callers that want to await it).
    @discardableResult
    func send(_ text: String) -> String? {
        guard ChannelThread.isSendable(text), let currentUserId else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let clientId = UUID().uuidString
        let replyId = chatRepliesEnabled ? replyTarget?.id : nil
        let replySnapshot = chatRepliesEnabled ? replyTarget.map(Self.optimisticReply(to:)) : nil
        let bubble = ChannelMessage(
            id: clientId,
            senderUid: currentUserId,
            text: trimmed,
            createdAt: Date(),
            createdAtIso: nil,
            clientId: clientId,
            deliveryState: .sending,
            replyTo: replySnapshot
        )
        pending.append(bubble)
        clearReply()
        recompute()
        deliver(bubble, replyToMessageId: replyId)
        return clientId
    }

    /// Retries a previously-``ChannelDeliveryState/failed`` bubble with the SAME
    /// clientId, so the send stays exactly-once. Only retryable failures act.
    func retry(_ message: ChannelMessage) {
        guard message.deliveryState == .failed, message.sendError?.isRetryable == true,
            let index = pending.firstIndex(where: { $0.id == message.id })
        else { return }
        let retried = Self.reset(pending[index])
        pending[index] = retried
        recompute()
        let replyId = chatRepliesEnabled ? retried.replyTo?.messageId : nil
        deliver(retried, replyToMessageId: replyId)
    }

    private func deliver(_ bubble: ChannelMessage, replyToMessageId: String?) {
        Task { [weak self, source] in
            let result = await source.post(
                text: bubble.text,
                clientId: bubble.clientId,
                replyToMessageId: replyToMessageId
            )
            guard let self else { return }
            switch result {
            case .sent:
                // The live listener reconciles the bubble away when the delivered
                // document arrives; nothing to do here beyond letting it.
                break
            case .failed(let error):
                self.markFailed(bubble.id, error: error)
            }
        }
    }

    private func markFailed(_ id: String, error: ChannelSendError) {
        guard let index = pending.firstIndex(where: { $0.id == id }) else { return }
        pending[index] = Self.fail(pending[index], with: error)
        recompute()
    }

    // MARK: - Older pages

    /// Requests the next older page (`*-list`). No-op while a page is in flight or
    /// pagination is exhausted, or when there is nothing to page before.
    func loadOlder() {
        guard olderPaging == .idle || olderPaging == .failed else { return }
        let real = ChannelThread.merge(older: older, live: live)
        guard let cursor = ChannelThread.oldestCursor(real) else { return }
        olderPaging = .loading
        Task { [weak self, source] in
            let result = await source.loadOlder(before: cursor)
            guard let self else { return }
            self.applyOlder(result)
        }
    }

    private func applyOlder(_ result: ChannelOlderResult) {
        switch result {
        case .loaded(let page):
            older = ChannelThread.merge(older: older, live: page.messages)
            olderPaging = page.hasMore ? .idle : .exhausted
            recompute()
        case .failed:
            olderPaging = .failed
        }
    }

    // MARK: - Reply composer (chatReplies-gated)

    /// Sets the reply target — no-op while ``chatRepliesEnabled`` is false, so the
    /// composer never enters reply mode with the feature dark.
    func setReplyTarget(_ message: ChannelMessage) {
        guard chatRepliesEnabled else { return }
        replyTarget = message
    }

    func clearReply() {
        replyTarget = nil
    }

    // MARK: - Reporting

    /// Reports a message; the outcome is fire-and-forget (the reporter is never
    /// told which failure occurred). Returns the binary result for callers that
    /// surface a toast.
    @discardableResult
    func report(_ message: ChannelMessage, reason: ChatReportReason) async -> ChannelReportResult {
        await source.report(messageId: message.id, reason: reason)
    }

    // MARK: - Bubble transitions

    private static func optimisticReply(to message: ChannelMessage) -> ChannelReplyTo {
        ChannelReplyTo(
            messageId: message.id,
            senderUid: message.senderUid,
            senderDisplayName: message.senderDisplayName,
            textPreview: message.text
        )
    }

    private static func fail(_ bubble: ChannelMessage, with error: ChannelSendError) -> ChannelMessage {
        copy(bubble, deliveryState: .failed, sendError: error)
    }

    private static func reset(_ bubble: ChannelMessage) -> ChannelMessage {
        copy(bubble, deliveryState: .sending, sendError: nil)
    }

    private static func copy(
        _ bubble: ChannelMessage,
        deliveryState: ChannelDeliveryState,
        sendError: ChannelSendError?
    ) -> ChannelMessage {
        ChannelMessage(
            id: bubble.id,
            senderUid: bubble.senderUid,
            text: bubble.text,
            senderDisplayName: bubble.senderDisplayName,
            senderAvatarPath: bubble.senderAvatarPath,
            createdAt: bubble.createdAt,
            createdAtIso: bubble.createdAtIso,
            mentionedUids: bubble.mentionedUids,
            clientId: bubble.clientId,
            deliveryState: deliveryState,
            sendError: sendError,
            replyTo: bubble.replyTo
        )
    }
}
