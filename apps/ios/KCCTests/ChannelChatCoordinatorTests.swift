import XCTest

@testable import KCC

/// Unit tests for the pure channel-chat orchestration: live emissions fold into
/// the display list, optimistic sends render then reconcile/fail, older-page
/// paging accumulates, and the `chatReplies` flag gates the reply affordance.
/// No Firebase — the source is a scripted fake.
final class ChannelChatCoordinatorTests: XCTestCase {

    // MARK: - Fake source

    private final class FakeChatSource: ChannelChatSource, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [ChannelMessagesState] = []
        private var continuations: [UUID: AsyncStream<ChannelMessagesState>.Continuation] = [:]
        private var postResults: [ChannelSendResult] = []
        private var olderResults: [ChannelOlderResult] = []

        let uid: String?
        private(set) var subscribeCount = 0
        private(set) var markReadCount = 0
        private(set) var postedTexts: [String] = []
        private(set) var postedReplyIds: [String?] = []

        init(uid: String? = "me") { self.uid = uid }

        func script(_ states: [ChannelMessagesState]) {
            lock.lock(); pending = states; lock.unlock()
        }
        func emit(_ state: ChannelMessagesState) {
            lock.lock(); let live = Array(continuations.values); lock.unlock()
            for c in live { c.yield(state) }
        }
        func scriptPost(_ results: [ChannelSendResult]) {
            lock.lock(); postResults = results; lock.unlock()
        }
        func scriptOlder(_ results: [ChannelOlderResult]) {
            lock.lock(); olderResults = results; lock.unlock()
        }

        func observeMessages() -> AsyncStream<ChannelMessagesState> {
            lock.lock(); subscribeCount += 1; let snapshots = pending; lock.unlock()
            return AsyncStream { continuation in
                for s in snapshots { continuation.yield(s) }
                let id = UUID()
                self.lock.lock(); self.continuations[id] = continuation; self.lock.unlock()
                continuation.onTermination = { [weak self] _ in
                    guard let self else { return }
                    self.lock.lock(); self.continuations[id] = nil; self.lock.unlock()
                }
            }
        }
        func loadOlder(before: String) async -> ChannelOlderResult {
            lock.withLock {
                olderResults.isEmpty ? ChannelOlderResult.failed : olderResults.removeFirst()
            }
        }
        func post(text: String, clientId: String?, replyToMessageId: String?) async -> ChannelSendResult {
            lock.withLock {
                postedTexts.append(text)
                postedReplyIds.append(replyToMessageId)
                return postResults.isEmpty ? ChannelSendResult.failed(.generic) : postResults.removeFirst()
            }
        }
        func markRead() async { lock.withLock { markReadCount += 1 } }
        func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult { .reported }
        func currentUserId() -> String? { uid }
    }

    // MARK: - Helpers

    @MainActor
    private func waitUntil(
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for condition", file: file, line: line)
    }

    private func serverMessage(_ id: String, sender: String = "you", secs: TimeInterval = 100) -> ChannelMessage {
        let date = Date(timeIntervalSince1970: secs)
        return ChannelMessage(
            id: id, senderUid: sender, text: "server \(id)",
            createdAt: date, createdAtIso: ChannelTime.isoString(date))
    }

    // MARK: - Load

    @MainActor
    func testInitialLoadingUntilFirstEmission() {
        let coordinator = ChannelChatCoordinator(source: FakeChatSource())
        XCTAssertTrue(coordinator.isInitialLoading)
        XCTAssertTrue(coordinator.messages.isEmpty)
    }

    @MainActor
    func testLoadedEmissionPopulatesMessagesAndMarksRead() async {
        let source = FakeChatSource()
        source.script([.loaded([serverMessage("a"), serverMessage("b", secs: 200)])])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        await waitUntil { coordinator.messages.map(\.id) == ["a", "b"] }
        XCTAssertFalse(coordinator.isInitialLoading)
        XCTAssertEqual(source.subscribeCount, 1)
        await waitUntil { source.markReadCount >= 1 }
    }

    @MainActor
    func testStartIsIdempotent() async {
        let source = FakeChatSource()
        source.script([.loaded([serverMessage("a")])])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        await waitUntil { !coordinator.messages.isEmpty }
        coordinator.start()
        XCTAssertEqual(source.subscribeCount, 1)
    }

    // MARK: - Sending

    @MainActor
    func testSendShowsOptimisticBubbleImmediately() {
        let source = FakeChatSource()
        source.scriptPost([.sent(messageId: "x", mentionedUids: [])])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        let clientId = coordinator.send("hello")
        XCTAssertNotNil(clientId)
        // Rendered synchronously as a sending bubble authored by the caller.
        let bubble = coordinator.messages.first { $0.id == clientId }
        XCTAssertEqual(bubble?.deliveryState, .sending)
        XCTAssertEqual(bubble?.senderUid, "me")
        XCTAssertEqual(bubble?.text, "hello")
    }

    @MainActor
    func testBlankDraftDoesNotSend() {
        let source = FakeChatSource()
        let coordinator = ChannelChatCoordinator(source: source)
        XCTAssertNil(coordinator.send("   "))
        XCTAssertTrue(coordinator.messages.isEmpty)
    }

    /// A signed-out caller (no uid) must not produce an optimistic bubble —
    /// there is no uid to author it with, and the post would fail server-side
    /// anyway. Regression for a review finding: send(_:) used to fall back to
    /// an empty senderUid instead of refusing to send.
    @MainActor
    func testSendIgnoredWhenSignedOut() {
        let source = FakeChatSource(uid: nil)
        let coordinator = ChannelChatCoordinator(source: source)
        XCTAssertNil(coordinator.send("hello"))
        XCTAssertTrue(coordinator.messages.isEmpty)
        XCTAssertTrue(source.postedTexts.isEmpty)
    }

    @MainActor
    func testSentBubbleReconciledAwayByDeliveredDocument() async {
        let source = FakeChatSource()
        source.script([.loaded([])])
        source.scriptPost([.sent(messageId: "ignored", mentionedUids: [])])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        await waitUntil { !coordinator.isInitialLoading }
        let clientId = coordinator.send("hello")!
        XCTAssertEqual(coordinator.messages.count, 1)
        // The delivered doc arrives from the listener with id == clientId.
        let delivered = ChannelMessage(
            id: clientId, senderUid: "me", text: "hello",
            createdAt: Date(timeIntervalSince1970: 999),
            createdAtIso: ChannelTime.isoString(Date(timeIntervalSince1970: 999)))
        source.emit(.loaded([delivered]))
        await waitUntil {
            coordinator.messages.count == 1
                && coordinator.messages.first?.deliveryState == .sent
        }
    }

    @MainActor
    func testFailedSendFlipsBubbleToFailedWithReason() async {
        let source = FakeChatSource()
        source.scriptPost([.failed(.notMember)])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        let clientId = coordinator.send("hi")!
        await waitUntil {
            coordinator.messages.first { $0.id == clientId }?.deliveryState == .failed
        }
        let bubble = coordinator.messages.first { $0.id == clientId }
        XCTAssertEqual(bubble?.sendError, .notMember)
        XCTAssertEqual(bubble?.sendError?.isRetryable, false)
    }

    @MainActor
    func testRetryResendsOnlyRetryableFailures() async {
        let source = FakeChatSource()
        source.scriptPost([.failed(.generic), .sent(messageId: "x", mentionedUids: [])])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        let clientId = coordinator.send("hi")!
        await waitUntil {
            coordinator.messages.first { $0.id == clientId }?.deliveryState == .failed
        }
        let failed = coordinator.messages.first { $0.id == clientId }!
        coordinator.retry(failed)
        // Same clientId reused (exactly-once); a second post recorded.
        await waitUntil { source.postedTexts.count == 2 }
        XCTAssertEqual(coordinator.messages.first { $0.id == clientId }?.deliveryState, .sending)
    }

    @MainActor
    func testRetryIgnoresNonRetryableFailure() async {
        let source = FakeChatSource()
        source.scriptPost([.failed(.notMember)])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        let clientId = coordinator.send("hi")!
        await waitUntil {
            coordinator.messages.first { $0.id == clientId }?.deliveryState == .failed
        }
        coordinator.retry(coordinator.messages.first { $0.id == clientId }!)
        // No second post — a not-member failure is terminal.
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(source.postedTexts.count, 1)
    }

    // MARK: - Reply-flag gating

    @MainActor
    func testReplyTargetIgnoredWhenFlagOff() {
        let source = FakeChatSource()
        let coordinator = ChannelChatCoordinator(source: source, chatRepliesEnabled: false)
        coordinator.setReplyTarget(serverMessage("a"))
        XCTAssertNil(coordinator.replyTarget)
    }

    @MainActor
    func testReplyTargetSetWhenFlagOnAndClearedAfterSend() async {
        let source = FakeChatSource()
        source.scriptPost([.sent(messageId: "x", mentionedUids: [])])
        let coordinator = ChannelChatCoordinator(source: source, chatRepliesEnabled: true)
        coordinator.start()
        coordinator.setReplyTarget(serverMessage("parent"))
        XCTAssertEqual(coordinator.replyTarget?.id, "parent")
        _ = coordinator.send("reply text")
        XCTAssertNil(coordinator.replyTarget)  // cleared on send
        await waitUntil { source.postedReplyIds.contains(where: { $0 == "parent" }) }
    }

    @MainActor
    func testSendOmitsReplyIdWhenFlagOff() async {
        let source = FakeChatSource()
        source.scriptPost([.sent(messageId: "x", mentionedUids: [])])
        let coordinator = ChannelChatCoordinator(source: source, chatRepliesEnabled: false)
        coordinator.start()
        // Even if a reply target were forced, the flag-off path sends no reply id.
        _ = coordinator.send("plain")
        await waitUntil { source.postedReplyIds.count == 1 }
        XCTAssertNil(source.postedReplyIds.first!)  // nil reply id sent
    }

    // MARK: - Older paging

    @MainActor
    func testLoadOlderAccumulatesAndExhausts() async {
        let source = FakeChatSource()
        source.script([.loaded([serverMessage("live", secs: 500)])])
        source.scriptOlder([
            .loaded(ChannelMessagesPage(messages: [serverMessage("old", secs: 100)], nextBefore: nil, hasMore: false))
        ])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        await waitUntil { coordinator.messages.map(\.id) == ["live"] }
        coordinator.loadOlder()
        await waitUntil { coordinator.messages.map(\.id) == ["old", "live"] }
        XCTAssertEqual(coordinator.olderPaging, .exhausted)
    }

    @MainActor
    func testLoadOlderFailureIsRetryableNotExhausted() async {
        let source = FakeChatSource()
        source.script([.loaded([serverMessage("live", secs: 500)])])
        source.scriptOlder([.failed])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        await waitUntil { coordinator.messages.map(\.id) == ["live"] }
        coordinator.loadOlder()
        await waitUntil { coordinator.olderPaging == .failed }
        XCTAssertNotEqual(coordinator.olderPaging, .exhausted)
    }

    // MARK: - Block filtering (source already filters; coordinator renders as-is)

    @MainActor
    func testCoordinatorRendersTheAlreadyBlockFilteredWindow() async {
        // The Firebase repo filters blocked authors before emitting; the
        // coordinator renders exactly what it receives. (The filter logic itself
        // is covered by ChatModelTests.testFilterHiddenAuthors…)
        let source = FakeChatSource()
        source.script([.loaded([serverMessage("visible", sender: "friend")])])
        let coordinator = ChannelChatCoordinator(source: source)
        coordinator.start()
        await waitUntil { coordinator.messages.map(\.id) == ["visible"] }
    }
}
