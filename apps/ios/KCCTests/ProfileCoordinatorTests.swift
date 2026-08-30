import XCTest

@testable import KCC

/// Unit tests for the pure own-profile orchestration: every repository
/// emission maps to the right ``ProfileUiState``, the config-less/no-session
/// wirings settle on unavailable, later listener updates flow through, and
/// the avatar path resolves to a URL exactly once per path. No Firebase —
/// the repository is a scripted fake (same conventions as
/// EventsCoordinatorTests).
final class ProfileCoordinatorTests: XCTestCase {

    // MARK: - fakes

    private final class FakeUserProfileRepository: UserProfileRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [UserProfileSnapshot] = []
        private var continuations: [UUID: AsyncStream<UserProfileSnapshot>.Continuation] = [:]
        private var avatarURLs: [String: URL] = [:]
        private(set) var subscribeCount = 0
        private(set) var observedUids: [String] = []
        private(set) var avatarResolveCount = 0

        /// Snapshots replayed to each FUTURE subscription (the listener's
        /// initial snapshot). The stream then stays open, like a real
        /// listener.
        func script(_ snapshots: [UserProfileSnapshot]) {
            lock.lock()
            pending = snapshots
            lock.unlock()
        }

        /// Pushes a snapshot to every LIVE subscription (a later listener
        /// update).
        func emit(_ snapshot: UserProfileSnapshot) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(snapshot)
            }
        }

        /// Registers the URL a given avatar path resolves to; an unregistered
        /// path resolves to nil (the real repository's failure posture).
        func scriptAvatarURL(_ url: URL, for path: String) {
            lock.lock()
            avatarURLs[path] = url
            lock.unlock()
        }

        func profileUpdates(uid: String) -> AsyncStream<UserProfileSnapshot> {
            lock.lock()
            subscribeCount += 1
            observedUids.append(uid)
            let snapshots = pending
            lock.unlock()
            return AsyncStream { continuation in
                for snapshot in snapshots {
                    continuation.yield(snapshot)
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

        func avatarDownloadURL(for avatarPath: String) async -> URL? {
            // NSLock is unavailable directly in async contexts; hop through a
            // synchronous helper so the critical section never suspends.
            resolveSynchronized(avatarPath)
        }

        private func resolveSynchronized(_ avatarPath: String) -> URL? {
            lock.lock()
            defer { lock.unlock() }
            avatarResolveCount += 1
            return avatarURLs[avatarPath]
        }
    }

    // MARK: - fixtures

    private static let profile = UserProfile(
        displayName: "Sebbe",
        bio: "E46:an är aldrig färdig.",
        avatarPath: nil
    )

    private static let avatarPath = "profileImages/uid-1/avatar-1.jpg"
    private static let avatarURL = URL(string: "https://example.test/avatar-1.jpg")!

    private static func profileWithAvatar(_ path: String? = avatarPath) -> UserProfile {
        UserProfile(displayName: "Sebbe", bio: nil, avatarPath: path)
    }

    /// Polls until `predicate` holds, yielding to let the coordinator's
    /// tasks drain. Fails the test on timeout.
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

    // MARK: - availability

    @MainActor
    func testNilRepositoryIsUnavailableAndStartIsANoOp() {
        let coordinator = ProfileCoordinator(repository: nil, uid: "uid-1")
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testNilUidIsUnavailableAndNeverSubscribes() {
        let repository = FakeUserProfileRepository()
        let coordinator = ProfileCoordinator(repository: repository, uid: nil)
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()
        XCTAssertEqual(repository.subscribeCount, 0)
    }

    // MARK: - state mapping

    @MainActor
    func testInitialStateIsLoadingBeforeStart() {
        let coordinator = ProfileCoordinator(
            repository: FakeUserProfileRepository(),
            uid: "uid-1"
        )
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testLoadedSnapshotBecomesLoadedForTheGivenUid() async {
        let repository = FakeUserProfileRepository()
        repository.script([.loaded(Self.profile)])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.state == .loaded(Self.profile) }
        XCTAssertEqual(repository.observedUids, ["uid-1"])
    }

    @MainActor
    func testMissingDocumentBecomesLoadedNil() async {
        let repository = FakeUserProfileRepository()
        repository.script([.loaded(nil)])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.state == .loaded(nil) }
    }

    @MainActor
    func testListenerFailureCarriesTheBareStatusCode() async {
        let repository = FakeUserProfileRepository()
        repository.script([.failed(code: "PERMISSION_DENIED")])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.state == .failed(code: "PERMISSION_DENIED") }
    }

    @MainActor
    func testLaterSnapshotUpdatesTheLoadedProfile() async {
        let repository = FakeUserProfileRepository()
        repository.script([.loaded(Self.profile)])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.state == .loaded(Self.profile) }

        let renamed = UserProfile(displayName: "Sebbe II", bio: nil, avatarPath: nil)
        repository.emit(.loaded(renamed))
        await wait { coordinator.state == .loaded(renamed) }
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    @MainActor
    func testErrorAfterLoadedSelfCorrectsOnTheNextSnapshot() async {
        let repository = FakeUserProfileRepository()
        repository.script([.loaded(Self.profile)])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.state == .loaded(Self.profile) }

        repository.emit(.failed(code: "UNAVAILABLE"))
        await wait { coordinator.state == .failed(code: "UNAVAILABLE") }

        repository.emit(.loaded(Self.profile))
        await wait { coordinator.state == .loaded(Self.profile) }
    }

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeUserProfileRepository()
        repository.script([.loaded(Self.profile)])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.state == .loaded(Self.profile) }
        coordinator.start()

        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(coordinator.state, .loaded(Self.profile))
    }

    // MARK: - avatar resolution

    @MainActor
    func testAvatarPathResolvesToADownloadURL() async {
        let repository = FakeUserProfileRepository()
        repository.scriptAvatarURL(Self.avatarURL, for: Self.avatarPath)
        repository.script([.loaded(Self.profileWithAvatar())])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.avatarURL == Self.avatarURL }
    }

    @MainActor
    func testUnchangedAvatarPathIsResolvedOnlyOnce() async {
        let repository = FakeUserProfileRepository()
        repository.scriptAvatarURL(Self.avatarURL, for: Self.avatarPath)
        repository.script([.loaded(Self.profileWithAvatar())])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.avatarURL == Self.avatarURL }

        // A profile edit re-emits the whole document with the same path —
        // the URL round-trip must not be re-paid. The name change makes the
        // state visibly advance, so the count is asserted only after the
        // second snapshot has definitely been applied.
        let renamed = UserProfile(
            displayName: "Sebbe II", bio: nil, avatarPath: Self.avatarPath
        )
        repository.emit(.loaded(renamed))
        await wait { coordinator.state == .loaded(renamed) }
        XCTAssertEqual(repository.avatarResolveCount, 1)
        XCTAssertEqual(coordinator.avatarURL, Self.avatarURL)
    }

    @MainActor
    func testRemovedAvatarPathClearsTheURL() async {
        let repository = FakeUserProfileRepository()
        repository.scriptAvatarURL(Self.avatarURL, for: Self.avatarPath)
        repository.script([.loaded(Self.profileWithAvatar())])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { coordinator.avatarURL == Self.avatarURL }

        repository.emit(.loaded(Self.profileWithAvatar(nil)))
        await wait { coordinator.avatarURL == nil }
    }

    @MainActor
    func testFailedAvatarResolutionKeepsThePlaceholder() async {
        let repository = FakeUserProfileRepository()
        // No scripted URL: resolution returns nil, like the real repository
        // on failure.
        repository.script([.loaded(Self.profileWithAvatar())])
        let coordinator = ProfileCoordinator(repository: repository, uid: "uid-1")

        coordinator.start()
        await wait { repository.avatarResolveCount == 1 }
        await wait { coordinator.state == .loaded(Self.profileWithAvatar()) }
        XCTAssertNil(coordinator.avatarURL)
    }
}
