import XCTest

@testable import KCC

final class BlockingCoordinatorTests: XCTestCase {
    private final class FakeRepository: BlockingRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var scripted: [BlockedUsersSnapshot] = []
        private var continuations: [UUID: AsyncStream<BlockedUsersSnapshot>.Continuation] = [:]
        private var storedCalls: [String] = []
        var error: Error?
        var delayNanoseconds: UInt64 = 0

        var calls: [String] { lock.withLock { storedCalls } }
        func script(_ snapshots: [BlockedUsersSnapshot]) { lock.withLock { scripted = snapshots } }

        func observeBlocked(uid: String) -> AsyncStream<BlockedUsersSnapshot> {
            let snapshots = lock.withLock { scripted }
            return AsyncStream { continuation in
                for snapshot in snapshots { continuation.yield(snapshot) }
                let id = UUID()
                self.lock.withLock { self.continuations[id] = continuation }
                continuation.onTermination = { [weak self] _ in
                    self?.lock.withLock { self?.continuations[id] = nil }
                }
            }
        }

        func block(targetUserId: String) async throws {
            lock.withLock { storedCalls.append("block:\(targetUserId)") }
            if delayNanoseconds > 0 { try await Task.sleep(nanoseconds: delayNanoseconds) }
            if let error { throw error }
        }

        func unblock(targetUserId: String) async throws {
            lock.withLock { storedCalls.append("unblock:\(targetUserId)") }
            if delayNanoseconds > 0 { try await Task.sleep(nanoseconds: delayNanoseconds) }
            if let error { throw error }
        }
    }

    private func user(_ id: String, at seconds: TimeInterval?) -> BlockedUser {
        BlockedUser(
            userId: id,
            displayName: nil,
            blockedAt: seconds.map(Date.init(timeIntervalSince1970:))
        )
    }

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
        XCTFail("Timed out waiting for blocking state", file: file, line: line)
    }

    func testDecodeUsesStoredIdAndTrimsNameAndDate() {
        let date = Date(timeIntervalSince1970: 42)
        let decoded = BlockedUser.decode(
            documentId: "document-id",
            fields: ["blockedUserId": " target ", "displayName": " Driver ", "createdAt": date]
        )
        XCTAssertEqual(decoded, BlockedUser(userId: "target", displayName: "Driver", blockedAt: date))
    }

    func testDecodeFallsBackToDocumentIdAndDropsBlankName() {
        let decoded = BlockedUser.decode(
            documentId: "target",
            fields: ["blockedUserId": " ", "displayName": "  "]
        )
        XCTAssertEqual(decoded, BlockedUser(userId: "target", displayName: nil, blockedAt: nil))
        XCTAssertNil(BlockedUser.decode(documentId: " ", fields: [:]))
    }

    func testSortingIsNewestFirstUndatedLastAndStable() {
        XCTAssertEqual(
            BlockedUsers.sortedForList([
                user("b", at: nil), user("d", at: 200), user("c", at: 300),
                user("a", at: 200),
            ]).map(\.userId),
            ["c", "a", "d", "b"]
        )
    }

    @MainActor
    func testUnavailableWithoutRepositoryOrUid() {
        XCTAssertEqual(BlockingCoordinator(repository: nil, uid: "me").state, .unavailable)
        XCTAssertEqual(BlockingCoordinator(repository: FakeRepository(), uid: nil).state, .unavailable)
    }

    @MainActor
    func testListenerMapsTerminalFailure() async {
        let repository = FakeRepository()
        let row = user("target", at: 100)
        repository.script([.loaded([]), .loaded([row]), .failed(code: "PERMISSION_DENIED")])
        let coordinator = BlockingCoordinator(repository: repository, uid: "me")
        coordinator.start()
        await waitUntil { coordinator.state == .failed(code: "PERMISSION_DENIED") }
    }

    @MainActor
    func testUnblockCallsAuthoritativeRepositoryAndSucceeds() async {
        let repository = FakeRepository()
        let coordinator = BlockingCoordinator(repository: repository, uid: "me")
        await coordinator.unblock(targetUserId: " target ")
        XCTAssertEqual(repository.calls, ["unblock:target"])
        XCTAssertEqual(coordinator.actionStatus, .succeeded)
    }

    @MainActor
    func testBlockIsSupportedForContextualBlockingCallers() async {
        let repository = FakeRepository()
        let coordinator = BlockingCoordinator(repository: repository, uid: "me")
        await coordinator.block(targetUserId: "target")
        XCTAssertEqual(repository.calls, ["block:target"])
        XCTAssertEqual(coordinator.actionStatus, .succeeded)
    }

    @MainActor
    func testSelfAndBlankTargetsFailWithoutCallingRepository() async {
        let repository = FakeRepository()
        let coordinator = BlockingCoordinator(repository: repository, uid: "me")
        await coordinator.block(targetUserId: " me ")
        XCTAssertEqual(coordinator.actionStatus, .failed(code: .invalidArgument))
        coordinator.resetActionStatus()
        await coordinator.unblock(targetUserId: " ")
        XCTAssertEqual(coordinator.actionStatus, .failed(code: .invalidArgument))
        XCTAssertTrue(repository.calls.isEmpty)
    }

    @MainActor
    func testCallableFailureKeepsOnlyStableCode() async {
        let repository = FakeRepository()
        repository.error = KccFunctionsError(code: .permissionDenied)
        let coordinator = BlockingCoordinator(repository: repository, uid: "me")
        await coordinator.unblock(targetUserId: "target")
        XCTAssertEqual(coordinator.actionStatus, .failed(code: .permissionDenied))
    }

    @MainActor
    func testMutationIsSingleFlightAndCancellationReturnsToIdle() async {
        let repository = FakeRepository()
        repository.delayNanoseconds = 5_000_000_000
        let coordinator = BlockingCoordinator(repository: repository, uid: "me")
        let first = Task { await coordinator.unblock(targetUserId: "one") }
        await waitUntil { coordinator.actionStatus == .working(targetUserId: "one") }
        await coordinator.unblock(targetUserId: "two")
        XCTAssertEqual(repository.calls, ["unblock:one"])
        first.cancel()
        await first.value
        XCTAssertEqual(coordinator.actionStatus, .idle)
    }
}
