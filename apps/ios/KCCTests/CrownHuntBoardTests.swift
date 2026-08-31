import XCTest

@testable import KCC

/// Pins the leaderboard ranking + composition — the ordering here MUST agree
/// with the server's `rankLeaderboard`, so it is unit-tested exactly like the
/// Android `CrownHuntBoard` object it mirrors.
final class CrownHuntBoardTests: XCTestCase {

    private func counter(_ uid: String, _ points: Int, _ crowns: Int) -> CrownLeaderboardCounter {
        CrownLeaderboardCounter(uid: uid, points: points, crownsCollected: crowns)
    }

    // MARK: - rank

    func testRankOrdersByPointsThenCrownsThenUid() {
        let ranked = CrownHuntBoard.rank([
            counter("c", 100, 5),
            counter("a", 200, 1),
            counter("b", 100, 9), // more crowns than "c" at equal points
            counter("d", 100, 5), // ties "c" on points+crowns → uid tiebreak
        ])
        XCTAssertEqual(ranked.map(\.counter.uid), ["a", "b", "c", "d"])
        XCTAssertEqual(ranked.map(\.rank), [1, 2, 3, 4])
    }

    func testRankOfEmptyIsEmpty() {
        XCTAssertTrue(CrownHuntBoard.rank([]).isEmpty)
    }

    // MARK: - board

    func testBoardFlagsViewerAndResolvesNames() {
        let board = CrownHuntBoard.board(
            counters: [counter("a", 200, 1), counter("me", 100, 1)],
            viewerUid: "me",
            names: ["a": "Alice"],
            seasonId: "2026-08"
        )
        XCTAssertEqual(board.seasonId, "2026-08")
        XCTAssertEqual(board.rows.first?.displayName, "Alice")
        XCTAssertFalse(board.rows[0].isViewer)
        XCTAssertTrue(board.rows[1].isViewer)
        XCTAssertEqual(board.viewerRank, 2)
    }

    func testBoardFallsBackToUidStubWhenNameMissingOrBlank() {
        let board = CrownHuntBoard.board(
            counters: [counter("abcdefghijkl", 10, 1), counter("blankname", 5, 1)],
            viewerUid: nil,
            names: ["blankname": "   "],
            seasonId: "2026-08"
        )
        // Missing name → first 8 chars of the uid; blank name → same stub rule.
        XCTAssertEqual(board.rows[0].displayName, "abcdefgh") // "abcdefghijkl".prefix(8)
        XCTAssertEqual(board.rows[1].displayName, "blanknam") // "blankname".prefix(8)
        XCTAssertNil(board.viewerRank)
    }

    func testViewerRankNilWhenViewerOutsideThePage() {
        let board = CrownHuntBoard.board(
            counters: [counter("a", 200, 1)],
            viewerUid: "not_on_board",
            names: [:],
            seasonId: "2026-08"
        )
        XCTAssertNil(board.viewerRank)
        XCTAssertFalse(board.rows.contains { $0.isViewer })
    }

    // MARK: - personalStats

    func testPersonalStatsNilWhenNothingToShow() {
        XCTAssertNil(
            CrownHuntBoard.personalStats(allTime: nil, season: nil, seasonRank: nil, rich: nil)
        )
    }

    func testPersonalStatsComposesFromCountersAndRich() {
        let rich = CrownUserStatsDoc(
            byRarity: [.rare: 2],
            streakCurrent: 4,
            streakBest: 9,
            seasonsWon: 1,
            rarest: .rare
        )
        let stats = CrownHuntBoard.personalStats(
            allTime: counter("me", 500, 20),
            season: counter("me", 120, 6),
            seasonRank: 3,
            rich: rich
        )
        XCTAssertEqual(stats?.points, 500)
        XCTAssertEqual(stats?.crownsCollected, 20)
        XCTAssertEqual(stats?.seasonPoints, 120)
        XCTAssertEqual(stats?.seasonCrowns, 6)
        XCTAssertEqual(stats?.seasonRank, 3)
        XCTAssertEqual(stats?.streakCurrent, 4)
        XCTAssertEqual(stats?.streakBest, 9)
        XCTAssertEqual(stats?.seasonsWon, 1)
        XCTAssertEqual(stats?.rarest, .rare)
        XCTAssertEqual(stats?.byRarity[.rare], 2)
    }

    func testPersonalStatsDefaultsZeroWhenOnlyRichPresent() {
        let stats = CrownHuntBoard.personalStats(
            allTime: nil,
            season: nil,
            seasonRank: nil,
            rich: CrownUserStatsDoc(
                byRarity: [:], streakCurrent: 1, streakBest: 1, seasonsWon: 0, rarest: nil
            )
        )
        XCTAssertNotNil(stats)
        XCTAssertEqual(stats?.points, 0)
        XCTAssertEqual(stats?.crownsCollected, 0)
        XCTAssertEqual(stats?.seasonPoints, 0)
    }
}
