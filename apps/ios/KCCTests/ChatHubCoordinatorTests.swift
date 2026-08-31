import XCTest

@testable import KCC

/// Unit tests for the Convoys-tab list coordinator and the hub scaffold: the
/// one-shot convoy load folds into the right state (loading/empty/loaded/failed),
/// grouping is applied, hub tab selection works, the hub degrades to nil
/// coordinators in a config-less build, and the hub presentation guard consumes
/// the unmodified `ShellNavigation.chatHubAllowed`.
final class ChatHubCoordinatorTests: XCTestCase {

    // MARK: - Fakes

    private final class FakeConvoyRepo: ConvoyChatRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var listResult: ConvoyListState
        private(set) var listCount = 0

        init(listResult: ConvoyListState) { self.listResult = listResult }
        func set(_ result: ConvoyListState) { lock.lock(); listResult = result; lock.unlock() }

        func listConvoys() async -> ConvoyListState {
            lock.withLock {
                listCount += 1
                return listResult
            }
        }
        func observeMessages(convoyId: String) -> AsyncStream<ChannelMessagesState> {
            AsyncStream { $0.finish() }
        }
        func loadOlder(convoyId: String, before: String) async -> ChannelOlderResult { .failed }
        func post(convoyId: String, text: String, clientId: String?, replyToMessageId: String?) async -> ChannelSendResult {
            .failed(.generic)
        }
        func markRead(convoyId: String) async {}
        func report(convoyId: String, messageId: String, reason: ChatReportReason) async -> ChannelReportResult {
            .reported
        }
        func currentUserId() -> String? { "me" }
    }

    private final class FakeCommunityRepo: CommunityChatRepository, @unchecked Sendable {
        func observeMessages() -> AsyncStream<ChannelMessagesState> { AsyncStream { $0.finish() } }
        func loadOlder(before: String) async -> ChannelOlderResult { .failed }
        func post(text: String, mentionedUids: [String], clientId: String?, replyToMessageId: String?) async -> ChannelSendResult {
            .failed(.generic)
        }
        func markRead() async {}
        func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult { .reported }
        func currentUserId() -> String? { "me" }
    }

    @MainActor
    private func waitUntil(
        timeout: TimeInterval = 2, file: StaticString = #filePath, line: UInt = #line, _ predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out", file: file, line: line)
    }

    private func convoy(_ id: String, status: String, secs: TimeInterval) -> ChatConvoy {
        ChatConvoy(convoyId: id, status: status, memberCount: 1, createdAt: Date(timeIntervalSince1970: secs))
    }

    // MARK: - ConvoyListCoordinator

    @MainActor
    func testConvoyListLoadedGroups() async {
        let repo = FakeConvoyRepo(listResult: .loaded([
            convoy("a", status: "active", secs: 100),
            convoy("e", status: "ended", secs: 200),
        ]))
        let coordinator = ConvoyListCoordinator(repository: repo)
        coordinator.start()
        await waitUntil {
            if case .loaded = coordinator.state { return true }
            return false
        }
        guard case .loaded(let grouped) = coordinator.state else { return XCTFail("expected loaded") }
        XCTAssertEqual(grouped.ongoing.map(\.convoyId), ["a"])
        XCTAssertEqual(grouped.past.map(\.convoyId), ["e"])
    }

    @MainActor
    func testConvoyListEmptyState() async {
        let repo = FakeConvoyRepo(listResult: .loaded([]))
        let coordinator = ConvoyListCoordinator(repository: repo)
        coordinator.start()
        await waitUntil { coordinator.state == .empty }
    }

    @MainActor
    func testConvoyListErrorAndRetry() async {
        let repo = FakeConvoyRepo(listResult: .error)
        let coordinator = ConvoyListCoordinator(repository: repo)
        coordinator.start()
        await waitUntil { coordinator.state == .failed }
        repo.set(.loaded([convoy("a", status: "active", secs: 100)]))
        coordinator.reload()
        await waitUntil {
            if case .loaded = coordinator.state { return true }
            return false
        }
        XCTAssertEqual(repo.listCount, 2)
    }

    @MainActor
    func testConvoyListStartIsIdempotent() async {
        let repo = FakeConvoyRepo(listResult: .loaded([convoy("a", status: "active", secs: 100)]))
        let coordinator = ConvoyListCoordinator(repository: repo)
        coordinator.start()
        await waitUntil {
            if case .loaded = coordinator.state { return true }
            return false
        }
        coordinator.start()
        XCTAssertEqual(repo.listCount, 1)
    }

    @MainActor
    func testMakeChatCoordinatorBindsConvoy() {
        let repo = FakeConvoyRepo(listResult: .loaded([]))
        let coordinator = ConvoyListCoordinator(repository: repo)
        let chat = coordinator.makeChatCoordinator(convoyId: "c1", chatRepliesEnabled: true)
        XCTAssertTrue(chat.chatRepliesEnabled)
        XCTAssertEqual(chat.currentUserId, "me")
    }

    // MARK: - ChatHubCoordinator

    @MainActor
    func testHubSelectsTabs() {
        let hub = ChatHubCoordinator(
            communityRepository: FakeCommunityRepo(),
            convoyRepository: FakeConvoyRepo(listResult: .loaded([])))
        XCTAssertEqual(hub.selectedTab, .community)
        hub.select(.convoys)
        XCTAssertEqual(hub.selectedTab, .convoys)
        hub.select(.notifications)
        XCTAssertEqual(hub.selectedTab, .notifications)
    }

    @MainActor
    func testHubWiresFunctionalTabsAndThreadsReplyFlag() {
        let hub = ChatHubCoordinator(
            communityRepository: FakeCommunityRepo(),
            convoyRepository: FakeConvoyRepo(listResult: .loaded([])),
            chatRepliesEnabled: true)
        XCTAssertNotNil(hub.communityChat)
        XCTAssertNotNil(hub.convoyList)
        XCTAssertTrue(hub.communityChat!.chatRepliesEnabled)
    }

    @MainActor
    func testHubConfiglessBuildHasNilCoordinators() {
        let hub = ChatHubCoordinator(communityRepository: nil, convoyRepository: nil)
        XCTAssertNil(hub.communityChat)
        XCTAssertNil(hub.convoyList)
        // Tabs still select — the view renders placeholders.
        hub.select(.convoys)
        XCTAssertEqual(hub.selectedTab, .convoys)
    }

    // MARK: - Presentation guard consumes ShellNavigation.chatHubAllowed

    func testCanPresentHubMatchesShellGate() {
        // Delegates to the unmodified Shell gate: only over a live map.
        XCTAssertTrue(ChatHubCoordinator.canPresentHub(cover: .none, navigating: false))
        XCTAssertTrue(ChatHubCoordinator.canPresentHub(cover: .opaque, navigating: true))
        XCTAssertFalse(ChatHubCoordinator.canPresentHub(cover: .opaque, navigating: false))
        XCTAssertFalse(ChatHubCoordinator.canPresentHub(cover: .transparent, navigating: false))
        // Parity with the source of truth.
        for cover in [MapCover.none, .transparent, .opaque] {
            for navigating in [true, false] {
                XCTAssertEqual(
                    ChatHubCoordinator.canPresentHub(cover: cover, navigating: navigating),
                    ShellNavigation.chatHubAllowed(cover: cover, navigating: navigating))
            }
        }
    }
}
