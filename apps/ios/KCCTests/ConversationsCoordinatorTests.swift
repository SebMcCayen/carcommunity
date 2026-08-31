import XCTest

@testable import KCC

/// Unit tests for the inbox orchestration: the live inbox states, the
/// hidden-set block filter (both signals: the marker rows never reach the
/// coordinator — pinned in DmModelsTests — and the `blockVisibility` set is
/// applied here), and retry re-subscription. No Firebase — scripted fakes.
final class ConversationsCoordinatorTests: XCTestCase {

    // MARK: - fakes

    final class FakeConversationsRepository: ConversationsRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [DmConversationsState] = []
        private var continuations: [UUID: AsyncStream<DmConversationsState>.Continuation] = [:]
        private(set) var subscribeCount = 0
        private(set) var observedUids: [String] = []

        func script(_ states: [DmConversationsState]) {
            lock.lock()
            pending = states
            lock.unlock()
        }

        func emit(_ state: DmConversationsState) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(state)
            }
        }

        func observeConversations(uid: String) -> AsyncStream<DmConversationsState> {
            lock.lock()
            subscribeCount += 1
            observedUids.append(uid)
            let states = pending
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

        func observeThread(conversationId: String) -> AsyncStream<DmThreadState> {
            AsyncStream { $0.finish() }
        }

        func sendMessage(toUid: String, text: String, clientId: String?) async -> DmSendResult {
            .failed(.generic)
        }

        func loadOlder(conversationId: String, before: String) async -> DmOlderResult {
            .failed
        }

        func markRead(conversationId: String) async {}
    }

    final class FakeBlockVisibilityRepository: BlockVisibilityRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [Set<String>]
        private var continuations: [UUID: AsyncStream<Set<String>>.Continuation] = [:]

        init(initial: [Set<String>] = [[]]) {
            pending = initial
        }

        func emit(_ hidden: Set<String>) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(hidden)
            }
        }

        func observeHiddenUids() -> AsyncStream<Set<String>> {
            lock.lock()
            let sets = pending
            lock.unlock()
            return AsyncStream { continuation in
                for set in sets {
                    continuation.yield(set)
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
    }

    private static func row(_ otherUid: String, unread: Int = 0) -> DmConversation {
        DmConversation(
            conversationId: dmPairId("me", otherUid),
            otherUser: DmUser(uid: otherUid, displayName: otherUid, avatarPath: nil),
            lastMessage: nil,
            unreadCount: unread,
            lastMessageAtMillis: 1_000
        )
    }

    @MainActor
    private func waitForState(
        of coordinator: ConversationsCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (DmConversationsState) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator.state) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail(
            "Timed out waiting for state; last: \(coordinator.state)",
            file: file,
            line: line
        )
    }

    // MARK: - states

    @MainActor
    func testLoadedInboxPassesThroughWithNoBlocks() async {
        let repository = FakeConversationsRepository()
        let rows = [Self.row("u1"), Self.row("u2")]
        repository.script([.loaded(rows)])
        let coordinator = ConversationsCoordinator(
            repository: repository,
            blockVisibility: FakeBlockVisibilityRepository(),
            uid: "me"
        )

        XCTAssertEqual(coordinator.state, .loading)
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(rows) }
        XCTAssertEqual(repository.observedUids, ["me"])
    }

    @MainActor
    func testErrorCarriesTheBareStatusCode() async {
        let repository = FakeConversationsRepository()
        repository.script([.error(code: "FAILED_PRECONDITION")])
        let coordinator = ConversationsCoordinator(
            repository: repository,
            blockVisibility: FakeBlockVisibilityRepository(),
            uid: "me"
        )

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .error(code: "FAILED_PRECONDITION") }
    }

    // MARK: - block gating

    @MainActor
    func testHiddenCounterpartyRowsAreDropped() async {
        let repository = FakeConversationsRepository()
        repository.script([.loaded([Self.row("u1"), Self.row("blocked")])])
        let coordinator = ConversationsCoordinator(
            repository: repository,
            blockVisibility: FakeBlockVisibilityRepository(initial: [["blocked"]]),
            uid: "me"
        )

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded([Self.row("u1")]) }
    }

    @MainActor
    func testAFreshBlockRefiltersTheLoadedInbox() async {
        let repository = FakeConversationsRepository()
        let rows = [Self.row("u1"), Self.row("u2")]
        repository.script([.loaded(rows)])
        let blocks = FakeBlockVisibilityRepository()
        let coordinator = ConversationsCoordinator(
            repository: repository,
            blockVisibility: blocks,
            uid: "me"
        )

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(rows) }

        // The hidden set is symmetric (blocked + blocked-by), so this covers
        // both "I blocked them" and "they blocked me": the row disappears
        // either way.
        blocks.emit(["u2"])
        await waitForState(of: coordinator) { $0 == .loaded([Self.row("u1")]) }

        // Unblocking restores the row whole.
        blocks.emit([])
        await waitForState(of: coordinator) { $0 == .loaded(rows) }
    }

    @MainActor
    func testLaterInboxSnapshotsStayFiltered() async {
        let repository = FakeConversationsRepository()
        repository.script([.loaded([Self.row("u1")])])
        let coordinator = ConversationsCoordinator(
            repository: repository,
            blockVisibility: FakeBlockVisibilityRepository(initial: [["hidden"]]),
            uid: "me"
        )

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded([Self.row("u1")]) }

        repository.emit(.loaded([Self.row("u1"), Self.row("hidden")]))
        await waitForState(of: coordinator) { $0 == .loaded([Self.row("u1")]) }
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    // MARK: - start/retry semantics

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeConversationsRepository()
        let rows = [Self.row("u1")]
        repository.script([.loaded(rows)])
        let coordinator = ConversationsCoordinator(
            repository: repository,
            blockVisibility: FakeBlockVisibilityRepository(),
            uid: "me"
        )

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(rows) }
        coordinator.start()

        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(coordinator.state, .loaded(rows))
    }

    @MainActor
    func testRetryResubscribesAndRecoversFromAFailure() async {
        let repository = FakeConversationsRepository()
        repository.script([.error(code: "UNAVAILABLE")])
        let coordinator = ConversationsCoordinator(
            repository: repository,
            blockVisibility: FakeBlockVisibilityRepository(),
            uid: "me"
        )

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .error(code: "UNAVAILABLE") }

        let rows = [Self.row("u1")]
        repository.script([.loaded(rows)])
        coordinator.retry()
        XCTAssertEqual(coordinator.state, .loading)
        await waitForState(of: coordinator) { $0 == .loaded(rows) }
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    // MARK: - new-dialogue picker

    @MainActor
    func testNewDialogueCoordinatorStates() async {
        final class ScriptedFriends: FriendsRepository, @unchecked Sendable {
            var result: FriendsResult = .failed(.generic)
            func list() async -> FriendsResult { result }
            func sendRequest(nickname: String) async -> SendRequestResult { .requested }
            func sendRequest(toUid: String) async -> SendRequestResult { .requested }
            func respond(requestId: String, accept: Bool) async -> RespondResult { .accepted }
            func cancelRequest(toUid: String) async -> CancelResult { .cancelled }
            func remove(friendUid: String) async -> RemoveResult { .removed }
        }

        let friends = ScriptedFriends()
        let coordinator = NewDialogueCoordinator(friends: friends)
        XCTAssertEqual(coordinator.state, .loading)

        await coordinator.load()
        XCTAssertEqual(coordinator.state, .error)

        // Retry recovers; eligible targets are established friends only,
        // blank uids dropped, name-ordered.
        friends.result = .loaded(
            FriendsData(
                friends: [
                    FriendSummary(uid: "b", displayName: "Örjan", avatarPath: nil, friendsSince: nil),
                    FriendSummary(uid: "", displayName: "Ghost", avatarPath: nil, friendsSince: nil),
                    FriendSummary(uid: "a", displayName: "Adam", avatarPath: nil, friendsSince: nil),
                ],
                incoming: [],
                outgoing: []
            )
        )
        await coordinator.load()
        guard case .ready(let targets) = coordinator.state else {
            return XCTFail("Expected ready, got \(coordinator.state)")
        }
        XCTAssertEqual(targets.map(\.uid), ["a", "b"])
    }
}
