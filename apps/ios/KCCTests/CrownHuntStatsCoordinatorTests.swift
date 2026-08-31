import XCTest

@testable import KCC

/// Unit tests for the stats orchestration: every repository emission maps to
/// the right ``CrownHuntStatsUiState``, and the terminal gates (no repo / no uid
/// / gated out) short-circuit to ``CrownHuntStatsUiState/unavailable`` without a
/// read. No Firebase — the repository is a scripted fake.
final class CrownHuntStatsCoordinatorTests: XCTestCase {

    private final class FakeStatsRepository: CrownHuntStatsRepository, @unchecked Sendable {
        private let snapshot: CrownStatsSnapshot
        private(set) var readCount = 0

        init(_ snapshot: CrownStatsSnapshot) { self.snapshot = snapshot }

        func stats(uid: String) -> AsyncStream<CrownStatsSnapshot> {
            readCount += 1
            let snapshot = snapshot
            return AsyncStream { continuation in
                continuation.yield(snapshot)
                continuation.finish()
            }
        }
    }

    private func board(rows: [CrownLeaderboardRow], seasonId: String = "2026-08") -> CrownSeasonBoard {
        CrownSeasonBoard(seasonId: seasonId, rows: rows, viewerRank: rows.first?.rank)
    }

    private func row(_ uid: String) -> CrownLeaderboardRow {
        CrownLeaderboardRow(
            rank: 1, uid: uid, displayName: uid, points: 10, crownsCollected: 1, isViewer: true
        )
    }

    private func personal() -> CrownPersonalStats {
        CrownPersonalStats(
            points: 100, crownsCollected: 5, seasonRank: 2, seasonPoints: 20, seasonCrowns: 2,
            byRarity: [:], streakCurrent: 3, streakBest: 5, seasonsWon: 0, rarest: nil
        )
    }

    // MARK: - state mapping

    @MainActor
    func testInitialStateIsLoading() {
        let coordinator = CrownHuntStatsCoordinator(
            repository: FakeStatsRepository(.loaded(CrownStatsData(personal: nil, board: board(rows: [])))),
            uid: "me",
            passesMemberGate: true
        )
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testLoadedWithPersonalBecomesLoaded() async {
        let data = CrownStatsData(personal: personal(), board: board(rows: []))
        let coordinator = CrownHuntStatsCoordinator(
            repository: FakeStatsRepository(.loaded(data)), uid: "me", passesMemberGate: true
        )
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(data) }
    }

    @MainActor
    func testLoadedWithBoardButNoPersonalBecomesLoaded() async {
        let data = CrownStatsData(personal: nil, board: board(rows: [row("me")]))
        let coordinator = CrownHuntStatsCoordinator(
            repository: FakeStatsRepository(.loaded(data)), uid: "me", passesMemberGate: true
        )
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(data) }
    }

    @MainActor
    func testNoPersonalAndEmptyBoardBecomesEmpty() async {
        let data = CrownStatsData(personal: nil, board: board(rows: []))
        let coordinator = CrownHuntStatsCoordinator(
            repository: FakeStatsRepository(.loaded(data)), uid: "me", passesMemberGate: true
        )
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .empty(seasonId: "2026-08") }
    }

    @MainActor
    func testFailureCarriesBareStatusCode() async {
        let coordinator = CrownHuntStatsCoordinator(
            repository: FakeStatsRepository(.failed(code: "PERMISSION_DENIED")),
            uid: "me",
            passesMemberGate: true
        )
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .failed(code: "PERMISSION_DENIED") }
    }

    // MARK: - terminal gates → unavailable

    @MainActor
    func testNilRepositoryIsUnavailable() {
        let coordinator = CrownHuntStatsCoordinator(
            repository: nil, uid: "me", passesMemberGate: true
        )
        coordinator.start()
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testNilUidIsUnavailable() {
        let repository = FakeStatsRepository(.loaded(CrownStatsData(personal: personal(), board: board(rows: []))))
        let coordinator = CrownHuntStatsCoordinator(
            repository: repository, uid: nil, passesMemberGate: true
        )
        coordinator.start()
        XCTAssertEqual(coordinator.state, .unavailable)
        XCTAssertEqual(repository.readCount, 0)
    }

    @MainActor
    func testFailingMemberGateIsUnavailable() {
        let repository = FakeStatsRepository(.loaded(CrownStatsData(personal: personal(), board: board(rows: []))))
        let coordinator = CrownHuntStatsCoordinator(
            repository: repository, uid: "me", passesMemberGate: false
        )
        coordinator.start()
        XCTAssertEqual(coordinator.state, .unavailable)
        XCTAssertEqual(repository.readCount, 0)
    }

    // MARK: - start/reload

    @MainActor
    func testStartIsIdempotent() async {
        let data = CrownStatsData(personal: personal(), board: board(rows: []))
        let repository = FakeStatsRepository(.loaded(data))
        let coordinator = CrownHuntStatsCoordinator(
            repository: repository, uid: "me", passesMemberGate: true
        )
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(data) }
        coordinator.start()
        XCTAssertEqual(repository.readCount, 1)
    }

    @MainActor
    private func waitForState(
        of coordinator: CrownHuntStatsCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (CrownHuntStatsUiState) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator.state) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out; last: \(coordinator.state)", file: file, line: line)
    }
}
