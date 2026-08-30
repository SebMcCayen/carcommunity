import Foundation
import Observation

/// Older-page pagination status for a thread (Android: `DmPageStatus`).
enum DmPageStatus: Equatable, Sendable {
    /// Older pages may exist and none is currently loading.
    case idle
    case loading
    /// Reached the beginning of the conversation — no older pages.
    case end
    /// The last older-page load failed transiently. NOT terminal: the "load
    /// older" affordance stays visible so the user can retry (unlike ``end``).
    case error
}

/// Orchestrates one open DM thread — the iOS port of Android's
/// `DmThreadCoordinator` plus the live-listener wiring its `ChatRoute` holds:
/// the live newest-window subscription, OPTIMISTIC send, older-page
/// pagination, and mark-read. Pure Swift (no Firebase/SwiftUI types) so it is
/// unit-testable with a fake repository.
///
/// Send is optimistic: ``send(text:)`` appends a local "sending" bubble to
/// the displayed thread IMMEDIATELY and fires `dm-sendMessage` in the
/// background. On success the bubble flips to ``DmDeliveryState/sent``; on
/// failure to ``DmDeliveryState/failed`` with a ``retry(clientId:)``
/// affordance — the user's message is never silently dropped. The bubble is
/// reconciled away (rendered exactly once) the moment the real document
/// arrives from the listener, matched by its clientId (which is also the
/// delivered doc's id).
///
/// Idempotency: each optimistic bubble carries a generated clientId used
/// verbatim as the message doc id, so a retry resends the SAME key and the
/// backend writes exactly one message / bumps unread once.
@MainActor
@Observable
final class ChatCoordinator {
    private let repository: ConversationsRepository
    private let selfUid: String
    /// The thread's other participant.
    let otherUid: String
    /// Canonical conversation id, derived locally (``dmPairId(_:_:)``) — no
    /// lookup needed; `dm-sendMessage` creates the document on the first
    /// message.
    let conversationId: String
    private let clock: @Sendable () -> Int64
    private let idGenerator: @Sendable () -> String

    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    /// True until the first live snapshot (or denied-as-empty emission)
    /// arrives.
    private(set) var threadLoading = true
    /// The live newest-window, chronological.
    private var liveMessages: [DmMessage] = []
    /// Accumulated older pages (chronological, deduplicated).
    private var olderMessages: [DmMessage] = []
    /// The caller's in-flight/failed optimistic bubbles, oldest-first.
    private var pendingMessages: [DmMessage] = []

    /// Server messages merged with the caller's optimistic bubbles; a bubble
    /// whose delivered doc has arrived (matched by clientId == doc id) is
    /// dropped, so an optimistic send and its snapshot render as exactly one
    /// message.
    private(set) var messages: [DmMessage] = []

    private(set) var pageStatus: DmPageStatus = .idle

    /// Increments on every successful send; drives the one-shot listener
    /// re-subscribe below and lets the UI react to a landed send.
    private(set) var sentCount = 0

    /// Gate so a burst of quick sends re-subscribes the (initially denied)
    /// listener AT MOST ONCE per thread — the first listen on a
    /// not-yet-created conversation is denied by the rules, so the first
    /// successful send must re-attach it.
    private var resubscribed = false

    init(
        repository: ConversationsRepository,
        selfUid: String,
        otherUid: String,
        clock: @escaping @Sendable () -> Int64 = {
            Int64((Date().timeIntervalSince1970 * 1000).rounded())
        },
        idGenerator: @escaping @Sendable () -> String = { UUID().uuidString }
    ) {
        self.repository = repository
        self.selfUid = selfUid
        self.otherUid = otherUid
        self.conversationId = dmPairId(selfUid, otherUid)
        self.clock = clock
        self.idGenerator = idGenerator
    }

    deinit {
        subscription?.cancel()
    }

    /// Begins observing the live thread and clears the caller's unread on
    /// open. Idempotent.
    func start() async {
        guard subscription == nil else { return }
        subscribe()
        // Mark read on open; new incoming messages while the thread is open
        // are handled per-snapshot in the stream loop.
        await markRead()
    }

    private func subscribe() {
        subscription?.cancel()
        let stream = repository.observeThread(conversationId: conversationId)
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                await self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: DmThreadState) async {
        guard case .loaded(let live) = snapshot else { return }
        threadLoading = false
        let previousNewestId = liveMessages.last?.id
        liveMessages = live
        // Reconcile the optimistic bubbles against every live snapshot: once
        // the real document lands, drop the matching pending bubble.
        if !pendingMessages.isEmpty {
            let liveIds = Set(live.map(\.id))
            pendingMessages.removeAll { liveIds.contains($0.id) }
        }
        rebuildDisplayed()
        // Mark read whenever a NEW INCOMING message lands while the thread is
        // open. A newest message that is the caller's own send carries no
        // unread to clear, so it must not trigger a needless markRead
        // callable (Android: `markReadIfIncoming`).
        if let newest = live.last, newest.id != previousNewestId, newest.senderUid == otherUid {
            await markRead()
        }
    }

    /// Whether the "load earlier messages" affordance should show: not at
    /// the confirmed beginning, and only once the window is full enough that
    /// older pages can exist (Android: `canLoadOlder`).
    var canLoadOlder: Bool {
        pageStatus != .end && messages.count >= Dm.messagesPageSize
    }

    /// Optimistically sends `text`: the "sending" bubble is appended
    /// synchronously (the UI updates before the suspending callable), then
    /// `dm-sendMessage` is dispatched with a fresh idempotency key.
    func send(text: String) async {
        guard DmThreadLogic.isSendable(text) else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let clientId = idGenerator()
        let optimistic = DmMessage(
            id: clientId,
            senderUid: selfUid,
            text: trimmed,
            createdAtMillis: clock(),
            createdAtIso: nil,
            clientId: clientId,
            deliveryState: .sending
        )
        pendingMessages.append(optimistic)
        rebuildDisplayed()
        await dispatch(clientId: clientId, text: trimmed)
    }

    /// Re-attempts a previously failed bubble, resending the SAME clientId so
    /// the backend stays exactly-once (no double post). A no-op if the bubble
    /// isn't found or isn't in a failed state, so a double-tap can't fire two
    /// resends.
    func retry(clientId: String) async {
        guard let target = pendingMessages.first(where: {
            $0.clientId == clientId && $0.deliveryState == .failed
        }) else { return }
        // Back to Sending and clear the prior error while the resend runs.
        updatePending(clientId: clientId) {
            $0.deliveryState = .sending
            $0.sendError = nil
        }
        await dispatch(clientId: clientId, text: target.text)
    }

    private func dispatch(clientId: String, text: String) async {
        let result = await repository.sendMessage(
            toUid: otherUid,
            text: text,
            clientId: clientId
        )
        switch result {
        case .sent:
            // Flip to Sent so the "sending" affordance clears on the ack; the
            // bubble is removed for good once the listener delivers the real
            // doc, matched by clientId.
            updatePending(clientId: clientId) {
                $0.deliveryState = .sent
                $0.sendError = nil
            }
            sentCount += 1
            // Re-subscribe once the first send creates the conversation
            // document (the initial listen was denied for the not-yet-
            // existing doc). At MOST once per thread: a burst of quick sends
            // must not repeatedly tear down and recreate the listener.
            if liveMessages.isEmpty && !resubscribed {
                resubscribed = true
                subscribe()
            }
        case .failed(let error):
            // Keep the SPECIFIC error so the UI can explain why and offer a
            // retry only when it could actually help (isRetryable).
            updatePending(clientId: clientId) {
                $0.deliveryState = .failed
                $0.sendError = error
            }
        }
        rebuildDisplayed()
    }

    /// Loads the page of messages older than the current oldest and
    /// accumulates it. No-op when there is no cursor, a page is already
    /// loading, or the beginning has been reached; a previous ``DmPageStatus/error``
    /// is retryable and does NOT block a fresh attempt. Only a genuine
    /// end-of-pagination (`hasMore == false` from the backend) ends in
    /// ``DmPageStatus/end`` — a transient error can never permanently hide
    /// the "load older" affordance.
    func loadOlder() async {
        guard let before = DmThreadLogic.oldestCursor(messages) else { return }
        guard pageStatus != .loading && pageStatus != .end else { return }
        pageStatus = .loading
        switch await repository.loadOlder(conversationId: conversationId, before: before) {
        case .loaded(let page):
            olderMessages = DmThreadLogic.merge(older: olderMessages, live: page.messages)
            pageStatus = page.hasMore ? .idle : .end
            rebuildDisplayed()
        case .failed:
            pageStatus = .error
        }
    }

    /// Marks the conversation read. Idempotent and best-effort (the
    /// repository swallows failures).
    func markRead() async {
        await repository.markRead(conversationId: conversationId)
    }

    private func updatePending(clientId: String, _ mutate: (inout DmMessage) -> Void) {
        for index in pendingMessages.indices where pendingMessages[index].clientId == clientId {
            mutate(&pendingMessages[index])
        }
    }

    private func rebuildDisplayed() {
        messages = DmThreadLogic.mergeWithPending(
            older: olderMessages,
            live: liveMessages,
            pending: pendingMessages
        )
    }
}
