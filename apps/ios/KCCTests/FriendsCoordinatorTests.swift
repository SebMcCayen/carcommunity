import XCTest

@testable import KCC

/// Unit tests for the friends orchestration — the iOS mirror of Android's
/// `FriendsCoordinatorTest`: load states, the add flow (validation,
/// ambiguity chooser, per-category failures, post-send reload), row actions
/// (respond/cancel/remove, their busy keys and error surfacing). No
/// Firebase — the repositories are scripted fakes.
final class FriendsCoordinatorTests: XCTestCase {

    // MARK: - fakes

    private final class FakeFriendsRepository: FriendsRepository, @unchecked Sendable {
        private let lock = NSLock()

        var listResults: [FriendsResult] = []
        var sendNicknameResult: SendRequestResult = .requested
        var sendUidResult: SendRequestResult = .requested
        var respondResult: RespondResult = .accepted
        var cancelResult: CancelResult = .cancelled
        var removeResult: RemoveResult = .removed

        private(set) var listCalls = 0
        private(set) var sentNicknames: [String] = []
        private(set) var sentUids: [String] = []
        private(set) var responded: [(requestId: String, accept: Bool)] = []
        private(set) var cancelled: [String] = []
        private(set) var removed: [String] = []

        // NSLock's bare lock()/unlock() are unavailable in async contexts;
        // the scoped withLock is the async-safe form (nothing suspends
        // inside the critical sections).
        func list() async -> FriendsResult {
            lock.withLock {
                listCalls += 1
                guard !listResults.isEmpty else { return .loaded(.empty) }
                return listResults.count == 1 ? listResults[0] : listResults.removeFirst()
            }
        }

        func sendRequest(nickname: String) async -> SendRequestResult {
            lock.withLock {
                sentNicknames.append(nickname)
                return sendNicknameResult
            }
        }

        func sendRequest(toUid: String) async -> SendRequestResult {
            lock.withLock {
                sentUids.append(toUid)
                return sendUidResult
            }
        }

        func respond(requestId: String, accept: Bool) async -> RespondResult {
            lock.withLock {
                responded.append((requestId, accept))
                return respondResult
            }
        }

        func cancelRequest(toUid: String) async -> CancelResult {
            lock.withLock {
                cancelled.append(toUid)
                return cancelResult
            }
        }

        func remove(friendUid: String) async -> RemoveResult {
            lock.withLock {
                removed.append(friendUid)
                return removeResult
            }
        }
    }

    private final class FakePointsRepository: FriendPointsRepository, @unchecked Sendable {
        private let lock = NSLock()
        var result: [String: Int64] = [:]
        private(set) var requestedUids: [[String]] = []

        func balances(for uids: [String]) async -> [String: Int64] {
            lock.withLock {
                requestedUids.append(uids)
                return result
            }
        }
    }

    private static func friend(_ uid: String, name: String = "F") -> FriendSummary {
        FriendSummary(uid: uid, displayName: name, avatarPath: nil, friendsSince: nil)
    }

    private static func data(friends: [FriendSummary] = []) -> FriendsData {
        FriendsData(friends: friends, incoming: [], outgoing: [])
    }

    // MARK: - load

    @MainActor
    func testLoadPublishesTheSnapshot() async {
        let repository = FakeFriendsRepository()
        let friends = [Self.friend("a")]
        repository.listResults = [.loaded(Self.data(friends: friends))]
        let coordinator = FriendsCoordinator(repository: repository)

        XCTAssertEqual(coordinator.status, .loading)
        await coordinator.load()

        XCTAssertEqual(
            coordinator.status,
            .loaded(friends: friends, incoming: [], outgoing: [], points: [:])
        )
    }

    @MainActor
    func testLoadFailureCarriesTheMappedError() async {
        let repository = FakeFriendsRepository()
        repository.listResults = [.failed(.temporarilyUnavailable)]
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.load()

        XCTAssertEqual(coordinator.status, .error(.temporarilyUnavailable))
    }

    @MainActor
    func testLoadOverlaysPointsAfterTheList() async {
        let repository = FakeFriendsRepository()
        let friends = [Self.friend("a"), Self.friend("b")]
        repository.listResults = [.loaded(Self.data(friends: friends))]
        let points = FakePointsRepository()
        points.result = ["a": 1_240]
        let coordinator = FriendsCoordinator(repository: repository, pointsRepository: points)

        await coordinator.load()

        XCTAssertEqual(
            coordinator.status,
            .loaded(friends: friends, incoming: [], outgoing: [], points: ["a": 1_240])
        )
        XCTAssertEqual(points.requestedUids, [["a", "b"]])
    }

    @MainActor
    func testEmptyFriendsListSkipsThePointsRead() async {
        let repository = FakeFriendsRepository()
        repository.listResults = [.loaded(.empty)]
        let points = FakePointsRepository()
        let coordinator = FriendsCoordinator(repository: repository, pointsRepository: points)

        await coordinator.load()

        XCTAssertTrue(points.requestedUids.isEmpty)
    }

    // MARK: - add flow

    @MainActor
    func testBlankNicknameFailsLocallyWithoutACall() async {
        let repository = FakeFriendsRepository()
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.sendRequest(nickname: "   ")

        XCTAssertEqual(coordinator.add, .error(.invalid))
        XCTAssertTrue(repository.sentNicknames.isEmpty)
    }

    @MainActor
    func testSendTrimsAndReloadsOnSuccess() async {
        let repository = FakeFriendsRepository()
        repository.sendNicknameResult = .requested
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.sendRequest(nickname: "  gt86 ")

        XCTAssertEqual(repository.sentNicknames, ["gt86"])
        XCTAssertEqual(coordinator.add, .sent(nowFriends: false))
        // A landed request changes the pending lists — the snapshot is
        // re-fetched.
        XCTAssertEqual(repository.listCalls, 1)
    }

    @MainActor
    func testSendAutoAcceptReportsNowFriends() async {
        let repository = FakeFriendsRepository()
        repository.sendNicknameResult = .nowFriends
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.sendRequest(nickname: "gt86")

        XCTAssertEqual(coordinator.add, .sent(nowFriends: true))
    }

    @MainActor
    func testAmbiguousNicknameOpensTheChooserWithoutReloading() async {
        let repository = FakeFriendsRepository()
        let candidates = [FriendUser(uid: "a", displayName: "gt_86", avatarPath: nil)]
        repository.sendNicknameResult = .ambiguous(candidates: candidates)
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.sendRequest(nickname: "gt")

        XCTAssertEqual(coordinator.add, .chooser(candidates: candidates))
        XCTAssertEqual(repository.listCalls, 0)
    }

    @MainActor
    func testChoosingACandidateResolvesTheAmbiguity() async {
        let repository = FakeFriendsRepository()
        repository.sendUidResult = .requested
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.chooseCandidate(uid: "a")

        XCTAssertEqual(repository.sentUids, ["a"])
        XCTAssertEqual(coordinator.add, .sent(nowFriends: false))
    }

    @MainActor
    func testSendFailureSurfacesTheSpecificCategory() async {
        let repository = FakeFriendsRepository()
        let coordinator = FriendsCoordinator(repository: repository)

        for expected in [FriendActionError.requestAlreadySent, .notAddable, .network] {
            repository.sendNicknameResult = .failed(expected)
            await coordinator.sendRequest(nickname: "gt86")
            XCTAssertEqual(coordinator.add, .error(expected))
            coordinator.resetAdd()
        }
        // Failures never reload.
        XCTAssertEqual(repository.listCalls, 0)
    }

    @MainActor
    func testResetAddReturnsToIdle() async {
        let repository = FakeFriendsRepository()
        repository.sendNicknameResult = .failed(.notFound)
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.sendRequest(nickname: "gt86")
        coordinator.resetAdd()

        XCTAssertEqual(coordinator.add, .idle)
    }

    // MARK: - respond / cancel / remove

    @MainActor
    func testAcceptReloadsTheSnapshot() async {
        let repository = FakeFriendsRepository()
        repository.respondResult = .accepted
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.accept(requestId: "r1")

        XCTAssertEqual(repository.responded.map(\.requestId), ["r1"])
        XCTAssertEqual(repository.responded.map(\.accept), [true])
        XCTAssertEqual(repository.listCalls, 1)
        XCTAssertNil(coordinator.actionError)
        XCTAssertTrue(coordinator.busyRows.isEmpty)
    }

    @MainActor
    func testDeclinePassesAcceptFalse() async {
        let repository = FakeFriendsRepository()
        repository.respondResult = .declined
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.decline(requestId: "r1")

        XCTAssertEqual(repository.responded.map(\.accept), [false])
    }

    @MainActor
    func testRespondFailureSurfacesTheErrorAndStillResyncs() async {
        let repository = FakeFriendsRepository()
        repository.respondResult = .failed(.requestGone)
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.accept(requestId: "r1")

        XCTAssertEqual(coordinator.actionError, .requestGone)
        // The request may be gone/handled server-side — resync so the stale
        // row disappears rather than lingering.
        XCTAssertEqual(repository.listCalls, 1)
    }

    @MainActor
    func testCancelReloadsOnBothSuccessAndMappedFailure() async {
        let repository = FakeFriendsRepository()
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.cancel(toUid: "b")
        XCTAssertEqual(repository.cancelled, ["b"])
        XCTAssertEqual(repository.listCalls, 1)

        repository.cancelResult = .failed(.network)
        await coordinator.cancel(toUid: "b")
        XCTAssertEqual(coordinator.actionError, .network)
        XCTAssertEqual(repository.listCalls, 2)
    }

    @MainActor
    func testRemoveFailureDoesNotReload() async {
        let repository = FakeFriendsRepository()
        repository.removeResult = .failed(.network)
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.remove(friendUid: "f1")

        XCTAssertEqual(coordinator.actionError, .network)
        XCTAssertEqual(repository.listCalls, 0)
    }

    @MainActor
    func testClearActionError() async {
        let repository = FakeFriendsRepository()
        repository.removeResult = .failed(.generic)
        let coordinator = FriendsCoordinator(repository: repository)

        await coordinator.remove(friendUid: "f1")
        XCTAssertEqual(coordinator.actionError, .generic)
        coordinator.clearActionError()
        XCTAssertNil(coordinator.actionError)
    }

    // MARK: - busy keys

    func testBusyKeysAreNamespacedPerAction() {
        // The ids come from three different spaces — a requestId, a
        // recipient uid, a friend uid — and could collide as bare strings.
        // The namespacing makes a cross-type collision impossible.
        XCTAssertEqual(FriendsCoordinator.respondBusyKey("x"), "respond:x")
        XCTAssertEqual(FriendsCoordinator.cancelBusyKey("x"), "cancel:x")
        XCTAssertEqual(FriendsCoordinator.removeBusyKey("x"), "remove:x")
        XCTAssertEqual(
            Set([
                FriendsCoordinator.respondBusyKey("x"),
                FriendsCoordinator.cancelBusyKey("x"),
                FriendsCoordinator.removeBusyKey("x"),
            ]).count,
            3
        )
    }
}
