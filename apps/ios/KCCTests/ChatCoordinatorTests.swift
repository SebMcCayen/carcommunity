import XCTest

@testable import KCC

/// Unit tests for one open DM thread — the iOS mirror of Android's
/// `DmThreadCoordinatorTest`: optimistic send (incl. per-category failure
/// codes and idempotent retry), reconcile against the live snapshot,
/// older-page pagination (transient error vs genuine end), mark-read
/// behavior, and the one-shot listener re-subscribe after the first send
/// creates the conversation. No Firebase — the repository is a scripted
/// fake.
final class ChatCoordinatorTests: XCTestCase {

    // MARK: - fake

    private final class FakeDmRepository: ConversationsRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pendingThread: [DmThreadState] = []
        private var continuations: [UUID: AsyncStream<DmThreadState>.Continuation] = [:]
        private(set) var threadSubscribeCount = 0
        private(set) var observedConversationIds: [String] = []

        var sendResult: DmSendResult = .sent(conversationId: "c", messageId: "m")
        var olderResult: DmOlderResult = .failed
        private(set) var sends: [(toUid: String, text: String, clientId: String?)] = []
        private(set) var olderCalls: [(conversationId: String, before: String)] = []
        private(set) var markReadCalls: [String] = []

        func scriptThread(_ states: [DmThreadState]) {
            lock.lock()
            pendingThread = states
            lock.unlock()
        }

        func emitThread(_ state: DmThreadState) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(state)
            }
        }

        func observeConversations(uid: String) -> AsyncStream<DmConversationsState> {
            AsyncStream { $0.finish() }
        }

        func observeThread(conversationId: String) -> AsyncStream<DmThreadState> {
            lock.lock()
            threadSubscribeCount += 1
            observedConversationIds.append(conversationId)
            let states = pendingThread
            lock.unlock()
            return AsyncStream { continuation in
                for state in states {
                    continuation.yield(state)
                }
                let id = UUID()
                self.lock.lock()
                self.continuations[id] = continuation
                self.lock.unlock()
                continuation.onTermination = { [weak self] _ in
                    guard let self else { return }
                    self.lock.lock()
                    self.continuations[id] = nil
                    self.lock.unlock()
                }
            }
        }

        // NSLock's bare lock()/unlock() are unavailable in async contexts;
        // the scoped withLock is the async-safe form (nothing suspends
        // inside the critical sections).
        func sendMessage(toUid: String, text: String, clientId: String?) async -> DmSendResult {
            lock.withLock {
                sends.append((toUid, text, clientId))
                return sendResult
            }
        }

        func loadOlder(conversationId: String, before: String) async -> DmOlderResult {
            lock.withLock {
                olderCalls.append((conversationId, before))
                return olderResult
            }
        }

        func markRead(conversationId: String) async {
            lock.withLock {
                markReadCalls.append(conversationId)
            }
        }
    }

    private static func message(
        _ id: String,
        at millis: Int64,
        sender: String,
        clientId: String? = nil
    ) -> DmMessage {
        DmMessage(
            id: id,
            senderUid: sender,
            text: "t-\(id)",
            createdAtMillis: millis,
            createdAtIso: millisToIso(millis),
            clientId: clientId
        )
    }

    @MainActor
    private func makeCoordinator(
        repository: FakeDmRepository,
        clientIds: [String] = ["client-1", "client-2", "client-3"]
    ) -> ChatCoordinator {
        let queue = ClientIdQueue(ids: clientIds)
        return ChatCoordinator(
            repository: repository,
            selfUid: "me",
            otherUid: "other",
            clock: { 5_000 },
            idGenerator: { queue.next() }
        )
    }

    private final class ClientIdQueue: @unchecked Sendable {
        private let lock = NSLock()
        private var ids: [String]

        init(ids: [String]) {
            self.ids = ids
        }

        func next() -> String {
            lock.lock()
            defer { lock.unlock() }
            return ids.isEmpty ? UUID().uuidString : ids.removeFirst()
        }
    }

    @MainActor
    private func waitFor(
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for condition", file: file, line: line)
    }

    // MARK: - conversation id

    @MainActor
    func testConversationIdIsTheDerivedPairId() {
        let coordinator = makeCoordinator(repository: FakeDmRepository())
        // Derived locally, no lookup — and identical to the backend
        // derivation (pinned byte-for-byte in DmModelsTests).
        XCTAssertEqual(coordinator.conversationId, dmPairId("me", "other"))
    }

    // MARK: - start / live thread

    @MainActor
    func testStartSubscribesMarksReadAndPublishesTheThread() async {
        let repository = FakeDmRepository()
        let live = [Self.message("m1", at: 1_000, sender: "other")]
        repository.scriptThread([.loaded(live)])
        let coordinator = makeCoordinator(repository: repository)

        XCTAssertTrue(coordinator.threadLoading)
        await coordinator.start()
        await waitFor { coordinator.messages == live }

        XCTAssertFalse(coordinator.threadLoading)
        // Mark read fires on open, and again for the incoming newest message
        // in the first snapshot (idempotent — Android's on-open LaunchedEffect
        // pair behaves the same way).
        await waitFor { repository.markReadCalls.count == 2 }
        XCTAssertEqual(Set(repository.markReadCalls), [coordinator.conversationId])
        // Idempotent start: no second subscription.
        await coordinator.start()
        XCTAssertEqual(repository.threadSubscribeCount, 1)
        XCTAssertEqual(repository.observedConversationIds, [coordinator.conversationId])
    }

    @MainActor
    func testNewIncomingMessageMarksReadAgainButOwnSendDoesNot() async {
        let repository = FakeDmRepository()
        repository.scriptThread([.loaded([])])
        let coordinator = makeCoordinator(repository: repository)

        await coordinator.start()
        await waitFor { !coordinator.threadLoading }
        XCTAssertEqual(repository.markReadCalls.count, 1)

        // The caller's OWN newest message carries no unread to clear — no
        // needless dm-markRead invocation.
        repository.emitThread(.loaded([Self.message("mine", at: 2_000, sender: "me")]))
        await waitFor { coordinator.messages.count == 1 }
        XCTAssertEqual(repository.markReadCalls.count, 1)

        // A newest INCOMING message clears the unread it just created.
        repository.emitThread(
            .loaded([
                Self.message("mine", at: 2_000, sender: "me"),
                Self.message("theirs", at: 3_000, sender: "other"),
            ])
        )
        await waitFor { repository.markReadCalls.count == 2 }
    }

    // MARK: - optimistic send

    @MainActor
    func testSendAppendsASendingBubbleThenFlipsToSent() async {
        let repository = FakeDmRepository()
        repository.scriptThread([.loaded([])])
        repository.sendResult = .sent(conversationId: "c", messageId: "client-1")
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { !coordinator.threadLoading }

        await coordinator.send(text: "  hej du  ")

        // Trimmed, idempotency key attached, addressed to the other member.
        XCTAssertEqual(repository.sends.count, 1)
        XCTAssertEqual(repository.sends[0].toUid, "other")
        XCTAssertEqual(repository.sends[0].text, "hej du")
        XCTAssertEqual(repository.sends[0].clientId, "client-1")
        // The bubble renders (id == clientId) and is acked.
        XCTAssertEqual(coordinator.messages.map(\.id), ["client-1"])
        XCTAssertEqual(coordinator.messages[0].deliveryState, .sent)
        XCTAssertEqual(coordinator.sentCount, 1)
    }

    @MainActor
    func testUnsendableDraftsAreDropped() async {
        let repository = FakeDmRepository()
        let coordinator = makeCoordinator(repository: repository)

        await coordinator.send(text: "   ")
        await coordinator.send(text: String(repeating: "a", count: 2_001))

        XCTAssertTrue(repository.sends.isEmpty)
        XCTAssertTrue(coordinator.messages.isEmpty)
    }

    @MainActor
    func testFailedSendKeepsTheBubbleWithTheSpecificError() async {
        let repository = FakeDmRepository()
        repository.scriptThread([.loaded([])])
        // The neutral cannot-deliver (not friends OR blocked — never
        // distinguished, so a block is never revealed) is terminal: no
        // retry affordance.
        repository.sendResult = .failed(.cannotDeliver)
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { !coordinator.threadLoading }

        await coordinator.send(text: "hej")

        XCTAssertEqual(coordinator.messages.count, 1)
        XCTAssertEqual(coordinator.messages[0].deliveryState, .failed)
        XCTAssertEqual(coordinator.messages[0].sendError, .cannotDeliver)
        XCTAssertEqual(coordinator.messages[0].sendError?.isRetryable, false)
        XCTAssertEqual(coordinator.sentCount, 0)
    }

    @MainActor
    func testRetryResendsTheSameClientIdOnlyFromFailed() async {
        let repository = FakeDmRepository()
        repository.scriptThread([.loaded([])])
        repository.sendResult = .failed(.generic)
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { !coordinator.threadLoading }

        await coordinator.send(text: "hej")
        XCTAssertEqual(coordinator.messages[0].sendError, .generic)
        XCTAssertEqual(coordinator.messages[0].sendError?.isRetryable, true)

        // Retry resends the SAME key — exactly-once server-side.
        repository.sendResult = .sent(conversationId: "c", messageId: "client-1")
        await coordinator.retry(clientId: "client-1")
        XCTAssertEqual(repository.sends.map(\.clientId), ["client-1", "client-1"])
        XCTAssertEqual(coordinator.messages[0].deliveryState, .sent)

        // A second retry of a no-longer-failed bubble is a no-op (a
        // double-tap can't fire two resends).
        await coordinator.retry(clientId: "client-1")
        XCTAssertEqual(repository.sends.count, 2)
    }

    @MainActor
    func testDeliveredDocumentReconcilesTheBubbleAway() async {
        let repository = FakeDmRepository()
        repository.scriptThread([.loaded([])])
        repository.sendResult = .sent(conversationId: "c", messageId: "client-1")
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { !coordinator.threadLoading }

        await coordinator.send(text: "hej")
        XCTAssertEqual(coordinator.messages.map(\.id), ["client-1"])

        // The real document (doc id == clientId) supersedes the bubble: the
        // message renders exactly once, from the server copy.
        let delivered = Self.message("client-1", at: 6_000, sender: "me", clientId: "client-1")
        repository.emitThread(.loaded([delivered]))
        await waitFor { coordinator.messages == [delivered] }
    }

    // MARK: - first-send re-subscribe

    @MainActor
    func testFirstSendResubscribesTheDeniedListenerExactlyOnce() async {
        let repository = FakeDmRepository()
        // The not-yet-created conversation reads as an empty thread.
        repository.scriptThread([.loaded([])])
        repository.sendResult = .sent(conversationId: "c", messageId: "client-1")
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { !coordinator.threadLoading }
        XCTAssertEqual(repository.threadSubscribeCount, 1)

        // The first successful send creates the document — re-subscribe so
        // the new thread streams in.
        await coordinator.send(text: "first")
        XCTAssertEqual(repository.threadSubscribeCount, 2)

        // A burst of further sends before the first snapshot arrives must
        // NOT tear the listener down again.
        await coordinator.send(text: "second")
        XCTAssertEqual(repository.threadSubscribeCount, 2)
    }

    // MARK: - pagination

    @MainActor
    func testLoadOlderAccumulatesAndEndsOnlyOnHasMoreFalse() async {
        let repository = FakeDmRepository()
        // A full newest-window so canLoadOlder is on and a cursor exists.
        let live = (0..<Dm.messagesPageSize).map {
            Self.message("live-\(String(format: "%02d", $0))", at: Int64(10_000 + $0), sender: "other")
        }
        repository.scriptThread([.loaded(live)])
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { coordinator.messages.count == Dm.messagesPageSize }
        XCTAssertTrue(coordinator.canLoadOlder)
        XCTAssertEqual(coordinator.pageStatus, .idle)

        // Page 1: more remain → idle, rows accumulate.
        let older = [Self.message("old-1", at: 1_000, sender: "me")]
        repository.olderResult = .loaded(
            DmMessagesPage(messages: older, nextBefore: millisToIso(1_000), hasMore: true)
        )
        await coordinator.loadOlder()
        XCTAssertEqual(coordinator.pageStatus, .idle)
        XCTAssertEqual(coordinator.messages.first?.id, "old-1")
        // The cursor was the earliest displayed message's ISO createdAt.
        XCTAssertEqual(repository.olderCalls.last?.before, millisToIso(10_000))

        // Page 2: the backend says no more → End; the affordance goes away.
        repository.olderResult = .loaded(
            DmMessagesPage(messages: [], nextBefore: nil, hasMore: false)
        )
        await coordinator.loadOlder()
        XCTAssertEqual(coordinator.pageStatus, .end)
        XCTAssertFalse(coordinator.canLoadOlder)

        // End is terminal: a further call is a no-op.
        await coordinator.loadOlder()
        XCTAssertEqual(repository.olderCalls.count, 2)
    }

    @MainActor
    func testTransientOlderFailureIsRetryableNotEnd() async {
        let repository = FakeDmRepository()
        let live = (0..<Dm.messagesPageSize).map {
            Self.message("live-\(String(format: "%02d", $0))", at: Int64(10_000 + $0), sender: "other")
        }
        repository.scriptThread([.loaded(live)])
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { coordinator.messages.count == Dm.messagesPageSize }

        repository.olderResult = .failed
        await coordinator.loadOlder()

        // A transient failure must NOT be conflated with "no more messages":
        // the affordance stays for a retry.
        XCTAssertEqual(coordinator.pageStatus, .error)
        XCTAssertTrue(coordinator.canLoadOlder)

        repository.olderResult = .loaded(
            DmMessagesPage(messages: [], nextBefore: nil, hasMore: true)
        )
        await coordinator.loadOlder()
        XCTAssertEqual(coordinator.pageStatus, .idle)
        XCTAssertEqual(repository.olderCalls.count, 2)
    }

    @MainActor
    func testPendingBubblesCountTowardNeitherTheGateNorTheCursor() async {
        let repository = FakeDmRepository()
        // One short of a full server window…
        let live = (0..<(Dm.messagesPageSize - 1)).map {
            Self.message("live-\(String(format: "%02d", $0))", at: Int64(10_000 + $0), sender: "other")
        }
        repository.scriptThread([.loaded(live)])
        // …and a send whose optimistic bubble (clock() = 5_000, EARLIER than
        // every server message, and with no createdAtIso) would top it up
        // and win the "oldest" pick if pagination looked at displayed rows.
        repository.sendResult = .failed(.generic)
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { coordinator.messages.count == Dm.messagesPageSize - 1 }

        await coordinator.send(text: "pending")
        await waitFor { coordinator.messages.count == Dm.messagesPageSize }

        // The affordance stays hidden: the SERVER window is not full.
        XCTAssertFalse(coordinator.canLoadOlder)

        // And a load derives its cursor from the server messages, never the
        // ISO-less bubble (which would silently no-op pagination).
        repository.olderResult = .loaded(
            DmMessagesPage(messages: [], nextBefore: nil, hasMore: true)
        )
        await coordinator.loadOlder()
        XCTAssertEqual(repository.olderCalls.map(\.before), [millisToIso(10_000)])
    }

    @MainActor
    func testLoadOlderNoopsWithoutACursor() async {
        let repository = FakeDmRepository()
        repository.scriptThread([.loaded([])])
        let coordinator = makeCoordinator(repository: repository)
        await coordinator.start()
        await waitFor { !coordinator.threadLoading }

        await coordinator.loadOlder()

        XCTAssertTrue(repository.olderCalls.isEmpty)
        XCTAssertEqual(coordinator.pageStatus, .idle)
    }
}
