import XCTest

@testable import KCC

/// Unit tests for the pure leaderboard orchestration: every repository
/// emission maps to the right ``LeaderboardUiState`` (including the fresh-board
/// `empty` and config-less `unavailable` cases), scope switching re-subscribes
/// for the new document, category switching does NOT re-subscribe, and the
/// picker never rests on a category the scope hides. No Firebase — the
/// repository is a scripted fake.
final class LeaderboardCoordinatorTests: XCTestCase {

    // MARK: - fakes

    private final class FakeLeaderboardRepository: LeaderboardRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [LeaderboardSnapshot] = []
        private var continuations: [UUID: AsyncStream<LeaderboardSnapshot>.Continuation] = [:]
        private(set) var subscribeCount = 0
        private(set) var lastScope: LeaderboardScope?
        private(set) var lastViewerUid: String?
        var uid: String?
        private var avatarURLs: [String: URL] = [:]
        private(set) var avatarResolveCount = 0
        /// When set, the NEXT avatarDownloadURL call suspends until
        /// ``releaseAvatarGate()`` — for pinning in-flight-resolution edges.
        private var avatarGate: CheckedContinuation<Void, Never>?
        private var avatarGateArmed = false
        /// True when release raced ahead of the gated call parking itself —
        /// the next park then resumes immediately instead of hanging.
        private var avatarGateReleased = false

        init(uid: String? = nil) { self.uid = uid }

        /// Snapshots replayed to each FUTURE subscription (the listener's
        /// initial snapshot). The stream then stays open, like a real listener.
        func script(_ snapshots: [LeaderboardSnapshot]) {
            lock.lock()
            pending = snapshots
            lock.unlock()
        }

        /// Pushes a snapshot to every LIVE subscription (a later listener
        /// update).
        func emit(_ snapshot: LeaderboardSnapshot) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live { continuation.yield(snapshot) }
        }

        func observeBoard(scope: LeaderboardScope, viewerUid: String?) -> AsyncStream<LeaderboardSnapshot> {
            lock.lock()
            subscribeCount += 1
            lastScope = scope
            lastViewerUid = viewerUid
            let snapshots = pending
            lock.unlock()
            return AsyncStream { continuation in
                for snapshot in snapshots { continuation.yield(snapshot) }
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

        /// Registers the URL a given avatar path resolves to; an
        /// unregistered path resolves to nil (the real repository's failure
        /// posture).
        func scriptAvatarURL(_ url: URL, for path: String) {
            lock.lock()
            avatarURLs[path] = url
            lock.unlock()
        }

        func avatarDownloadURL(for avatarPath: String) async -> URL? {
            let gated = beginAvatarResolve()
            if gated {
                await withCheckedContinuation { continuation in
                    parkOrResumeAvatar(continuation)
                }
            }
            return lookupAvatarURL(avatarPath)
        }

        /// Counts the attempt and consumes the gate arming, synchronously.
        private func beginAvatarResolve() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            avatarResolveCount += 1
            let gated = avatarGateArmed
            avatarGateArmed = false
            return gated
        }

        private func lookupAvatarURL(_ avatarPath: String) -> URL? {
            lock.lock()
            defer { lock.unlock() }
            return avatarURLs[avatarPath]
        }

        private func parkOrResumeAvatar(_ continuation: CheckedContinuation<Void, Never>) {
            lock.lock()
            if avatarGateReleased {
                avatarGateReleased = false
                lock.unlock()
                continuation.resume()
            } else {
                avatarGate = continuation
                lock.unlock()
            }
        }

        /// Arms the gate: the NEXT avatarDownloadURL call suspends until
        /// ``releaseAvatarGate()``.
        func holdNextAvatarResolve() {
            lock.lock()
            avatarGateArmed = true
            lock.unlock()
        }

        func releaseAvatarGate() {
            lock.lock()
            let gate = avatarGate
            avatarGate = nil
            if gate == nil { avatarGateReleased = true }
            lock.unlock()
            gate?.resume()
        }

        func currentUserId() -> String? { uid }
    }

    // MARK: - fixtures

    private static func board(
        _ category: LeaderboardCategory,
        uids: [String]
    ) -> LeaderboardCategoryBoard {
        LeaderboardCategoryBoard(
            category: category,
            entries: uids.enumerated().map { index, uid in
                LeaderboardEntry(
                    rank: index + 1,
                    uid: uid,
                    displayName: uid,
                    avatarPath: nil,
                    value: Double(100 - index),
                    isViewer: false
                )
            }
        )
    }

    /// An all-time board where crownPoints has rows and the rest are empty.
    private static func loadedAllTime(crownPointUids: [String] = ["a", "b"]) -> LeaderboardSnapshot {
        .loaded(
            LeaderboardBoard.categories(for: .allTime).map { category in
                category == .crownPoints
                    ? board(category, uids: crownPointUids)
                    : LeaderboardCategoryBoard(category: category, entries: [])
            }
        )
    }

    /// An all-time board where every category is empty (fresh / missing doc).
    private static func emptyAllTime() -> LeaderboardSnapshot {
        .loaded(
            LeaderboardBoard.categories(for: .allTime).map {
                LeaderboardCategoryBoard(category: $0, entries: [])
            }
        )
    }

    @MainActor
    private func waitForState(
        of coordinator: LeaderboardCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (LeaderboardUiState) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator.state) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for state; last: \(coordinator.state)", file: file, line: line)
    }

    /// Polls until `predicate` holds, yielding to let in-flight resolutions
    /// drain. Fails the test on timeout — same shape as
    /// `GarageCoordinatorTests.wait`.
    @MainActor
    private func wait(
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

    // MARK: - construction / unavailable

    @MainActor
    func testNilRepositoryRestsUnavailable() {
        let coordinator = LeaderboardCoordinator(repository: nil)
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()
        // start / reload / select are no-ops without a repository.
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.reload()
        coordinator.select(scope: .thisMonth)
        XCTAssertEqual(coordinator.state, .unavailable)
        XCTAssertEqual(coordinator.scope, .allTime)
    }

    @MainActor
    func testInitialStateIsLoadingBeforeStart() {
        let coordinator = LeaderboardCoordinator(repository: FakeLeaderboardRepository())
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(coordinator.scope, .allTime)
        XCTAssertEqual(coordinator.selectedCategory, .crownPoints)
    }

    // MARK: - state mapping

    @MainActor
    func testLoadedBoardWithEntriesBecomesLoaded() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) {
            if case .loaded = $0 { return true }
            return false
        }
        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(repository.lastScope, .allTime)
        XCTAssertEqual(coordinator.selectedCategoryBoard?.entries.map(\.uid), ["a", "b"])
    }

    @MainActor
    func testBoardWithEveryCategoryEmptyBecomesEmpty() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.emptyAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .empty }
        XCTAssertNil(coordinator.selectedCategoryBoard)
    }

    @MainActor
    func testListenerFailureCarriesTheBareStatusCode() async {
        let repository = FakeLeaderboardRepository()
        repository.script([.failed(code: "PERMISSION_DENIED")])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .failed(code: "PERMISSION_DENIED") }
    }

    @MainActor
    func testLaterSnapshotUpdatesALoadedBoard() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime(crownPointUids: ["a"])])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) {
            if case .loaded = $0 { return true }
            return false
        }
        XCTAssertEqual(coordinator.selectedCategoryBoard?.entries.count, 1)

        repository.emit(Self.loadedAllTime(crownPointUids: ["a", "b", "c"]))
        await waitForState(of: coordinator) { _ in coordinator.selectedCategoryBoard?.entries.count == 3 }
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    // MARK: - viewer flagging

    @MainActor
    func testViewerUidIsThreadedFromTheRepository() async {
        let repository = FakeLeaderboardRepository(uid: "me")
        repository.script([Self.loadedAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) {
            if case .loaded = $0 { return true }
            return false
        }
        XCTAssertEqual(repository.lastViewerUid, "me")
    }

    // MARK: - start / reload semantics

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) {
            if case .loaded = $0 { return true }
            return false
        }
        coordinator.start()
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    @MainActor
    func testReloadReturnsToLoadingThenReSubscribes() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 != .loading }

        repository.script([])
        coordinator.reload()
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    @MainActor
    func testReloadRecoversFromAFailure() async {
        let repository = FakeLeaderboardRepository()
        repository.script([.failed(code: "UNAVAILABLE")])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .failed(code: "UNAVAILABLE") }

        repository.script([Self.loadedAllTime()])
        coordinator.reload()
        await waitForState(of: coordinator) {
            if case .loaded = $0 { return true }
            return false
        }
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    // MARK: - scope switching

    @MainActor
    func testSelectingANewScopeReSubscribesForThatDocument() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 != .loading }

        coordinator.select(scope: .thisMonth)
        XCTAssertEqual(coordinator.scope, .thisMonth)
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(repository.subscribeCount, 2)
        XCTAssertEqual(repository.lastScope, .thisMonth)
    }

    @MainActor
    func testSelectingTheSameScopeIsANoOp() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 != .loading }

        coordinator.select(scope: .allTime)
        // No tear-down of the live listener.
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    @MainActor
    func testSwitchingToThisMonthWhileStreakSelectedFallsBackToFirstCategory() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)
        coordinator.start()
        await waitForState(of: coordinator) { $0 != .loading }

        // Streak is an all-time-only category.
        coordinator.select(category: .streak)
        XCTAssertEqual(coordinator.selectedCategory, .streak)

        coordinator.select(scope: .thisMonth)
        // This-month does not publish streak, so the picker falls back.
        XCTAssertFalse(coordinator.availableCategories.contains(.streak))
        XCTAssertEqual(coordinator.selectedCategory, .crownPoints)
    }

    // MARK: - category switching

    @MainActor
    func testSelectingACategoryDoesNotReSubscribe() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let coordinator = LeaderboardCoordinator(repository: repository)
        coordinator.start()
        await waitForState(of: coordinator) { $0 != .loading }

        coordinator.select(category: .distance)
        XCTAssertEqual(coordinator.selectedCategory, .distance)
        // Every category rides on the one document read — no new listener, and
        // the loaded state is untouched.
        XCTAssertEqual(repository.subscribeCount, 1)
        if case .loaded = coordinator.state {} else {
            XCTFail("Category switch must not leave the loaded state; got \(coordinator.state)")
        }
        // The distance board is present (empty here) and now the selected one.
        XCTAssertEqual(coordinator.selectedCategoryBoard?.category, .distance)
    }

    @MainActor
    func testSelectingAnUnavailableCategoryIsIgnored() {
        let repository = FakeLeaderboardRepository()
        let coordinator = LeaderboardCoordinator(repository: repository)
        // On this-month, streak is not offered.
        coordinator.select(scope: .thisMonth)
        coordinator.select(category: .streak)
        XCTAssertNotEqual(coordinator.selectedCategory, .streak)
    }

    @MainActor
    func testAvailableCategoriesTrackTheScope() {
        let coordinator = LeaderboardCoordinator(repository: FakeLeaderboardRepository())
        XCTAssertTrue(coordinator.availableCategories.contains(.streak))
        coordinator.select(scope: .thisMonth)
        XCTAssertFalse(coordinator.availableCategories.contains(.streak))
        XCTAssertEqual(coordinator.availableCategories.count, 5)
    }

    // MARK: - avatar URL resolution

    @MainActor
    func testAvatarURLResolvesOncePerPath() async {
        let repository = FakeLeaderboardRepository()
        let path = "userAvatars/uid-a/avatar.jpg"
        let url = URL(string: "https://example.test/avatar.jpg")!
        repository.scriptAvatarURL(url, for: path)
        let coordinator = LeaderboardCoordinator(repository: repository)

        let first = await coordinator.avatarURL(for: path)
        XCTAssertEqual(first, url)

        // A later call for the SAME path (a row's `.task` re-running across
        // view re-creations) must not re-pay the round-trip: the cache hit
        // returns immediately.
        let second = await coordinator.avatarURL(for: path)
        XCTAssertEqual(second, url)
        XCTAssertEqual(repository.avatarResolveCount, 1)
    }

    @MainActor
    func testFailedAvatarResolutionKeepsThePlaceholderAndIsNotRetried() async {
        let repository = FakeLeaderboardRepository()
        let path = "userAvatars/uid-a/avatar.jpg"
        // No scripted URL: resolution returns nil (the real repository's
        // failure posture).
        let coordinator = LeaderboardCoordinator(repository: repository)

        let first = await coordinator.avatarURL(for: path)
        XCTAssertNil(first)

        // The negative cache: a later call for the same path must NOT
        // re-attempt it — otherwise every row re-render would turn into a
        // Storage round-trip.
        let second = await coordinator.avatarURL(for: path)
        XCTAssertNil(second)
        XCTAssertEqual(repository.avatarResolveCount, 1)
    }

    @MainActor
    func testConcurrentCallsForTheSamePathDoNotDuplicateTheResolution() async {
        let repository = FakeLeaderboardRepository()
        let path = "userAvatars/uid-a/avatar.jpg"
        let url = URL(string: "https://example.test/avatar.jpg")!
        repository.scriptAvatarURL(url, for: path)
        repository.holdNextAvatarResolve()
        let coordinator = LeaderboardCoordinator(repository: repository)

        // The podium and the list row both resolve the SAME top member's
        // avatar concurrently — the second call must await the first's
        // in-flight resolution rather than starting its own.
        let first = Task { await coordinator.avatarURL(for: path) }
        await wait { repository.avatarResolveCount == 1 }
        let second = Task { await coordinator.avatarURL(for: path) }
        // Give the second call a chance to (wrongly) start its own
        // resolution before the gate is released.
        await Task.yield()
        XCTAssertEqual(repository.avatarResolveCount, 1)

        repository.releaseAvatarGate()
        let firstResult = await first.value
        let secondResult = await second.value
        XCTAssertEqual(firstResult, url)
        XCTAssertEqual(secondResult, url)
        XCTAssertEqual(repository.avatarResolveCount, 1)
    }

    @MainActor
    func testReloadRetriesAFailedAvatarResolution() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let path = "userAvatars/uid-a/avatar.jpg"
        let coordinator = LeaderboardCoordinator(repository: repository)
        coordinator.start()
        await waitForState(of: coordinator) { $0 != .loading }

        let first = await coordinator.avatarURL(for: path)
        XCTAssertNil(first)
        XCTAssertEqual(repository.avatarResolveCount, 1)

        // The photo becomes reachable; the explicit reload affordance clears
        // the negative cache and resolves it on the next call.
        let url = URL(string: "https://example.test/avatar.jpg")!
        repository.scriptAvatarURL(url, for: path)
        coordinator.reload()
        let second = await coordinator.avatarURL(for: path)
        XCTAssertEqual(second, url)
        XCTAssertEqual(repository.avatarResolveCount, 2)
    }

    @MainActor
    func testScopeSwitchRetriesAFailedAvatarResolution() async {
        let repository = FakeLeaderboardRepository()
        repository.script([Self.loadedAllTime()])
        let path = "userAvatars/uid-a/avatar.jpg"
        let coordinator = LeaderboardCoordinator(repository: repository)
        coordinator.start()
        await waitForState(of: coordinator) { $0 != .loading }

        let first = await coordinator.avatarURL(for: path)
        XCTAssertNil(first)
        XCTAssertEqual(repository.avatarResolveCount, 1)

        // A scope switch re-subscribes exactly like reload(); it must clear
        // the same negative cache.
        let url = URL(string: "https://example.test/avatar.jpg")!
        repository.scriptAvatarURL(url, for: path)
        coordinator.select(scope: .thisMonth)
        let second = await coordinator.avatarURL(for: path)
        XCTAssertEqual(second, url)
        XCTAssertEqual(repository.avatarResolveCount, 2)
    }
}
