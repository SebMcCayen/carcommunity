import XCTest

@testable import KCC

final class PointsCoordinatorTests: XCTestCase {
    private final class FakeRepository: PointsRepository, @unchecked Sendable {
        var balances: [Int64?] = []
        var snapshots: [PointsEntriesSnapshot] = []
        private(set) var balanceSubscriptions = 0
        private(set) var entrySubscriptions = 0

        func observeBalance(uid _: String) -> AsyncStream<Int64?> {
            balanceSubscriptions += 1
            let values = balances
            return AsyncStream { continuation in
                values.forEach { continuation.yield($0) }
            }
        }

        func observeEntries(uid _: String) -> AsyncStream<PointsEntriesSnapshot> {
            entrySubscriptions += 1
            let values = snapshots
            return AsyncStream { continuation in
                values.forEach { continuation.yield($0) }
            }
        }
    }

    @MainActor
    func testUnavailableWithoutRepository() {
        let coordinator = PointsCoordinator(repository: nil, uid: "uid")
        XCTAssertEqual(coordinator.entriesState, .unavailable)
        coordinator.start()
        XCTAssertEqual(coordinator.entriesState, .unavailable)
    }

    @MainActor
    func testStartFoldsBalanceEntriesAndProfileCredits() async {
        let repository = FakeRepository()
        repository.balances = [120]
        repository.snapshots = [.loaded([
            entry("spend", -10, 300),
            entry("new", 25, 200),
            entry("old", 5, 100),
        ])]
        let coordinator = PointsCoordinator(repository: repository, uid: "uid")
        coordinator.start()
        await wait { coordinator.balance == 120 && coordinator.recentEarnings.count == 2 }
        XCTAssertEqual(coordinator.recentEarnings.map(\.id), ["new", "old"])
    }

    @MainActor
    func testFailureIsRetryableAndStartIsIdempotent() async {
        let repository = FakeRepository()
        repository.snapshots = [.failed(code: "PERMISSION_DENIED")]
        let coordinator = PointsCoordinator(repository: repository, uid: "uid")
        coordinator.start()
        coordinator.start()
        await wait {
            coordinator.entriesState == .failed(code: "PERMISSION_DENIED")
        }
        XCTAssertEqual(repository.entrySubscriptions, 1)
        coordinator.reload()
        XCTAssertEqual(repository.entrySubscriptions, 2)
    }

    private func entry(_ id: String, _ amount: Int64, _ time: TimeInterval) -> PointsEntry {
        PointsEntry(
            id: id,
            amount: amount,
            balanceAfter: nil,
            description: id,
            createdAt: Date(timeIntervalSince1970: time)
        )
    }

    @MainActor
    private func wait(
        timeout: TimeInterval = 2,
        until predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(for: .milliseconds(1))
        }
        XCTFail("Timed out waiting for condition")
    }
}
