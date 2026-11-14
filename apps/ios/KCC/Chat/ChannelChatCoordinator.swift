import Foundation
import Observation

/// The channel-agnostic surface a ``ChannelChatCoordinator`` drives — one bound
/// channel (the community channel, or one convoy's channel). Both
/// ``CommunityChatRepository`` and ``ConvoyChatRepository`` are adapted to this
/// so the SAME coordinator and screen render both, mirroring Android's single
/// `ChannelChatCoordinator` used by both `CommunityChannelRoute` and
/// `ConvoyChannelRoute`.
protocol ChannelChatSource: Sendable {
    var reactionRoom: MessageReactionRoom? { get }
    func observeMessages() -> AsyncStream<ChannelMessagesState>
    func loadOlder(before: String) async -> ChannelOlderResult
    /// Community forwards the accepted @mention set; convoy always sends none.
    func post(text: String, clientId: String?, replyToMessageId: String?) async -> ChannelSendResult
    func markRead() async
    func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult
    func avatarDownloadURL(for avatarPath: String) async -> URL?
    func currentUserId() -> String?
}

/// Adapts a ``CommunityChatRepository`` to ``ChannelChatSource``. The @-mention
/// picker is a later slice, so posts carry an empty accepted set for now (a
/// delivered message's ``ChannelMessage/mentionedUids`` still decodes; there is
/// no UI highlighting of it yet).
extension ChannelChatSource { var reactionRoom: MessageReactionRoom? { nil } }

struct CommunityChatSource: ChannelChatSource {
    var reactionRoom: MessageReactionRoom? { MessageReactionRoom(scope: "community", roomID: "global") }
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
    func avatarDownloadURL(for avatarPath: String) async -> URL? {
        await repository.avatarDownloadURL(for: avatarPath)
    }
    func currentUserId() -> String? { repository.currentUserId() }
}

/// Adapts one convoy's channel (a bound `convoyId`) to ``ChannelChatSource``.
struct ConvoyChatSource: ChannelChatSource {
    var reactionRoom: MessageReactionRoom? { MessageReactionRoom(scope: "convoy", roomID: convoyId) }
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
    func avatarDownloadURL(for avatarPath: String) async -> URL? {
        await repository.avatarDownloadURL(for: avatarPath)
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
    var reactionRoom: MessageReactionRoom? { source.reactionRoom }
    /// Retained with this account/channel, across Chat source/tab view swaps.
    var draft = "" { didSet { draftRevision += 1 } }
    private var draftRevision = 0
    private struct DraftRecovery {
        var revision: Int
        let text: String
        let reply: ChannelMessage?
        let replyRevision: Int
        var restored = false
    }
    private var draftRecovery: [String: DraftRecovery] = [:]
    private var replyRevision = 0
    let cooldown: ChatSendCooldown
    private var readingActive = true

    /// Retained Chat views may be covered or backgrounded; incoming messages
    /// must stay unread until the actual channel becomes visible again.
    func setReadingActive(_ active: Bool) {
        guard active != readingActive else { return }
        readingActive = active
        if active, currentUserId != nil {
            Task { [source] in await source.markRead() }
        }
    }
    @ObservationIgnored private var avatarURLs: [String: URL] = [:]
    @ObservationIgnored private var avatarResolutions: [String: Task<URL?, Never>] = [:]
    @ObservationIgnored private var attemptedAvatarPaths: Set<String> = []

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

    /// Bumped every ``subscribe()`` — i.e. on ``start()``'s first call and on
    /// every ``reload()``. An in-flight ``loadOlder()`` task captures this at
    /// call time and re-checks it before applying its result, so a page that
    /// resolves after an intervening reload is dropped instead of wrongly
    /// repopulating `older` post-reset.
    private var loadGeneration = 0

    init(source: ChannelChatSource, chatRepliesEnabled: Bool = ChatFeatureFlags.chatRepliesDefault,
         cooldown: ChatSendCooldown? = nil) {
        self.source = source
        self.currentUserId = source.currentUserId()
        self.cooldown = cooldown ?? ChatSendCooldowns.forUser(source.currentUserId())
        self.chatRepliesEnabled = chatRepliesEnabled
    }

    deinit {
        subscription?.cancel()
    }

    /// Repeated messages and concurrent rows share one Storage resolution per path.
    /// A reload permits failed paths to be tried again; a new avatar path is
    /// independent of any cached result for an older photo.
    func avatarURL(for avatarPath: String?) async -> URL? {
        guard let avatarPath, !avatarPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        if let cached = avatarURLs[avatarPath] { return cached }
        if let inFlight = avatarResolutions[avatarPath] { return await inFlight.value }
        guard attemptedAvatarPaths.insert(avatarPath).inserted else { return nil }
        let task = Task { [source] in await source.avatarDownloadURL(for: avatarPath) }
        avatarResolutions[avatarPath] = task
        let url = await task.value
        avatarResolutions[avatarPath] = nil
        avatarURLs[avatarPath] = url
        return url
    }

    /// Begins observing on first appearance. Idempotent — a second call keeps the
    /// live subscription and current state instead of flashing back to loading
    /// (matches ``EventsCoordinator/start()``). Also stamps the read marker for a
    /// signed-in caller — subscribing still proceeds when signed out (so the UI
    /// can render its empty/denied state), but the marker call is skipped since
    /// it would only fail server-side with `unauthenticated`.
    func start() {
        guard subscription == nil else { return }
        subscribe()
        guard readingActive, currentUserId != nil else { return }
        Task { [source] in await source.markRead() }
    }

    /// Tears the listener down and re-subscribes from scratch (the retry
    /// affordance) — ``EventsCoordinator/reload()`` semantics. Also clears any
    /// active reply target: without this a stale reply banner could survive a
    /// reload and quote it onto the next send.
    func reload() {
        attemptedAvatarPaths = Set(avatarURLs.keys).union(avatarResolutions.keys)
        older = []
        pending = []
        draftRecovery.removeAll()
        olderPaging = .idle
        clearReply()
        subscribe()
    }

    private func subscribe() {
        subscription?.cancel()
        loadGeneration += 1
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
            let previousNewestId = live.last?.id
            live = messages
            recompute()
            // Re-stamp the marker only for a NEW incoming message — the newest
            // id must have actually changed, not just be incoming — mirroring
            // Social/ChatCoordinator.apply(_:) and Android's
            // `markReadIfIncoming`. Without the id-change check, a metadata-only
            // Firestore update or an unrelated re-emission of the same window
            // (e.g. a blockVisibility change) would re-fire markRead for a
            // message already marked read; without the incoming check, our own
            // post reconciling would too. Both are needless backend traffic.
            if readingActive, let newest = messages.last, newest.id != previousNewestId,
                let currentUserId, newest.senderUid != currentUserId {
                Task { [source] in await source.markRead() }
            }
        }
    }

    /// Merges live + older + pending into the display list. A pending bubble
    /// whose delivered document has arrived in the server set is dropped
    /// (``ChannelThread/mergeWithPending``), so an optimistic send renders as
    /// exactly one message.
    private func recompute() {
        let deliveredIDs = Set((older + live).map(\.id))
        for id in Array(draftRecovery.keys) where deliveredIDs.contains(id) { acknowledgeDraft(id) }
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
        guard !cooldown.isBlocked, ChannelThread.isSendable(text), let currentUserId else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let existing = pending.last(where: {
            $0.text == trimmed && $0.deliveryState == .failed && $0.sendError?.isRetryable == true &&
            $0.replyTo?.messageId == (chatRepliesEnabled ? replyTarget?.id : nil)
        }) {
            retry(existing)
            clearReply()
            return existing.id
        }
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

    /// Recovery is tied to this exact cleared draft revision; later typing wins.
    @discardableResult
    func sendDraft() -> String? {
        let text = draft
        let reply = replyTarget
        guard let id = send(text) else { return nil }
        draft = ""
        draftRecovery[id] = DraftRecovery(revision: draftRevision, text: text, reply: reply, replyRevision: replyRevision)
        return id
    }

    /// Retries a previously-``ChannelDeliveryState/failed`` bubble with the SAME
    /// clientId, so the send stays exactly-once. Only retryable failures act.
    func retry(_ message: ChannelMessage) {
        guard !cooldown.isBlocked, message.deliveryState == .failed, message.sendError?.isRetryable == true,
            let index = pending.firstIndex(where: { $0.id == message.id })
        else { return }
        guard pending[index].deliveryState == .failed else { return }
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
                self.acknowledgeDraft(bubble.id)
                // The live listener reconciles the bubble away when the delivered
                // document arrives; nothing to do here beyond letting it.
                break
            case .failed(let error):
                if case .rateLimited(let limit) = error { self.cooldown.apply(limit) }
                if var recovery = self.draftRecovery[bubble.id],
                   self.pending.contains(where: { $0.id == bubble.id }),
                   self.draftRevision == recovery.revision, self.draft.isEmpty {
                    self.draft = recovery.text
                    if self.replyRevision == recovery.replyRevision, self.replyTarget == nil {
                        self.replyTarget = recovery.reply
                    }
                    recovery.revision = self.draftRevision
                    recovery.restored = true
                    self.draftRecovery[bubble.id] = recovery
                }
                self.markFailed(bubble.id, error: error)
            }
        }
    }

    private func acknowledgeDraft(_ id: String) {
        guard let recovery = draftRecovery.removeValue(forKey: id), recovery.restored,
              draftRevision == recovery.revision, draft == recovery.text,
              replyRevision == recovery.replyRevision else { return }
        draft = ""
        if replyRevision == recovery.replyRevision { replyTarget = nil }
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
        // Captured so a reload()/re-subscribe while this call is in flight can
        // be detected below — the generation will have moved on by the time
        // this resolves, and the (now stale) page is dropped instead of
        // repopulating `older` after the reset.
        let generation = loadGeneration
        Task { [weak self, source] in
            let result = await source.loadOlder(before: cursor)
            guard let self, self.loadGeneration == generation else { return }
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
        replyRevision += 1
        replyTarget = message
    }

    func clearReply() {
        replyRevision += 1
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
