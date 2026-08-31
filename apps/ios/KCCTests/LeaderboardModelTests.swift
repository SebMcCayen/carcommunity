import XCTest

@testable import KCC

/// Pure leaderboard-domain logic — the iOS counterpart of Android's
/// `LeaderboardBoardTest`: scope → document id, per-scope category sets, the
/// board fold (rank filtering, viewer flagging, name fallback), the podium
/// split, value formatting, the tolerant wire decoding, and the season clock.
/// No Firebase — every input is a plain value or dictionary.
final class LeaderboardModelTests: XCTestCase {

    // MARK: - fixtures

    private func rawRow(
        rank: Int,
        uid: String,
        displayName: String = "Name",
        avatarPath: String? = nil,
        value: Double = 100
    ) -> RawLeaderboardRow {
        RawLeaderboardRow(
            rank: rank,
            uid: uid,
            displayName: displayName,
            avatarPath: avatarPath,
            value: value
        )
    }

    // MARK: - scopeDocId

    func testScopeDocIdIsFixedForAllTime() {
        let id = LeaderboardBoard.scopeDocId(.allTime, seasonId: "2026-08")
        XCTAssertEqual(id, LeaderboardBoard.allTimeDocId)
        XCTAssertEqual(id, "alltime")
    }

    func testScopeDocIdIsTheSeasonIdForThisMonth() {
        XCTAssertEqual(LeaderboardBoard.scopeDocId(.thisMonth, seasonId: "2026-08"), "2026-08")
    }

    func testScopeDocIdDoesNotEvaluateSeasonIdForAllTime() {
        // The season id is a lazy autoclosure: it must NOT run for all-time.
        var evaluated = false
        _ = LeaderboardBoard.scopeDocId(
            .allTime,
            seasonId: { evaluated = true; return "2026-08" }()
        )
        XCTAssertFalse(evaluated)
    }

    // MARK: - categoriesFor

    func testAllTimeCarriesEveryCategoryInRenderOrder() {
        XCTAssertEqual(
            LeaderboardBoard.categories(for: .allTime),
            [.crownPoints, .distance, .events, .convoys, .waves, .streak]
        )
    }

    func testThisMonthOmitsTheAllTimeOnlyStreak() {
        let categories = LeaderboardBoard.categories(for: .thisMonth)
        XCTAssertEqual(categories, [.crownPoints, .distance, .events, .convoys, .waves])
        XCTAssertFalse(categories.contains(.streak))
    }

    // MARK: - board fold

    func testBoardMapsRowsToEntriesInEveryScopeCategory() {
        let raw: [String: [RawLeaderboardRow]] = [
            "crownPoints": [rawRow(rank: 1, uid: "a", value: 500)]
        ]
        let boards = LeaderboardBoard.board(scope: .allTime, rawByCategory: raw, viewerUid: nil)

        // Every category present (render order), even those with no rows.
        XCTAssertEqual(boards.map(\.category), LeaderboardBoard.categories(for: .allTime))
        XCTAssertEqual(boards.first?.entries.count, 1)
        XCTAssertEqual(boards.first?.entries.first?.uid, "a")
        XCTAssertEqual(boards.first?.entries.first?.value, 500)
        // A category with no wire key is present but empty.
        XCTAssertEqual(boards.first(where: { $0.category == .distance })?.entries, [])
    }

    func testBoardDropsNonPositiveRanks() {
        let raw: [String: [RawLeaderboardRow]] = [
            "crownPoints": [
                rawRow(rank: 0, uid: "zero"),
                rawRow(rank: -1, uid: "neg"),
                rawRow(rank: 1, uid: "keep"),
            ]
        ]
        let entries = LeaderboardBoard.board(scope: .allTime, rawByCategory: raw, viewerUid: nil)
            .first?.entries
        XCTAssertEqual(entries?.map(\.uid), ["keep"])
    }

    func testBoardPreservesServerRowOrderVerbatim() {
        // Server order is authoritative; the client never re-ranks even if the
        // ranks look out of order.
        let raw: [String: [RawLeaderboardRow]] = [
            "crownPoints": [
                rawRow(rank: 2, uid: "second", value: 10),
                rawRow(rank: 1, uid: "first", value: 5),
            ]
        ]
        let entries = LeaderboardBoard.board(scope: .allTime, rawByCategory: raw, viewerUid: nil)
            .first?.entries
        XCTAssertEqual(entries?.map(\.uid), ["second", "first"])
    }

    func testBoardFlagsTheViewersOwnRow() {
        let raw: [String: [RawLeaderboardRow]] = [
            "crownPoints": [rawRow(rank: 1, uid: "me"), rawRow(rank: 2, uid: "other")]
        ]
        let entries = LeaderboardBoard.board(scope: .allTime, rawByCategory: raw, viewerUid: "me")
            .first?.entries
        XCTAssertEqual(entries?.first { $0.uid == "me" }?.isViewer, true)
        XCTAssertEqual(entries?.first { $0.uid == "other" }?.isViewer, false)
    }

    func testBoardFallsBackToAUidStubForABlankName() {
        let raw: [String: [RawLeaderboardRow]] = [
            "crownPoints": [rawRow(rank: 1, uid: "abcdef1234567890", displayName: "   ")]
        ]
        let name = LeaderboardBoard.board(scope: .allTime, rawByCategory: raw, viewerUid: nil)
            .first?.entries.first?.displayName
        XCTAssertEqual(name, "abcdef12")
    }

    // MARK: - podiumSplit

    func testPodiumSplitTakesTopThreeAndRest() {
        let entries = (1...5).map {
            LeaderboardEntry(rank: $0, uid: "u\($0)", displayName: "u\($0)", avatarPath: nil, value: 0, isViewer: false)
        }
        let split = LeaderboardBoard.podiumSplit(entries)
        XCTAssertEqual(split.top.map(\.rank), [1, 2, 3])
        XCTAssertEqual(split.rest.map(\.rank), [4, 5])
    }

    func testPodiumSplitWithFewerThanThreeHasEmptyRest() {
        let entries = (1...2).map {
            LeaderboardEntry(rank: $0, uid: "u\($0)", displayName: "u\($0)", avatarPath: nil, value: 0, isViewer: false)
        }
        let split = LeaderboardBoard.podiumSplit(entries)
        XCTAssertEqual(split.top.count, 2)
        XCTAssertTrue(split.rest.isEmpty)
    }

    // MARK: - displayValue

    func testDisplayValueConvertsMetresToWholeKilometres() {
        XCTAssertEqual(LeaderboardBoard.displayValue(.distanceKm, value: 12_400), 12)
        XCTAssertEqual(LeaderboardBoard.displayValue(.distanceKm, value: 12_600), 13)
    }

    func testDisplayValueRoundsOtherFormatsToWholeUnits() {
        XCTAssertEqual(LeaderboardBoard.displayValue(.crownPoints, value: 499.6), 500)
        XCTAssertEqual(LeaderboardBoard.displayValue(.count, value: 3), 3)
        XCTAssertEqual(LeaderboardBoard.displayValue(.days, value: 7.2), 7)
    }

    func testDisplayValueClampsNegativeAndNonFiniteToZero() {
        XCTAssertEqual(LeaderboardBoard.displayValue(.crownPoints, value: -50), 0)
        XCTAssertEqual(LeaderboardBoard.displayValue(.distanceKm, value: .nan), 0)
        XCTAssertEqual(LeaderboardBoard.displayValue(.count, value: .infinity), 0)
    }

    // MARK: - tolerant wire decoding

    func testRawRowDecodesAFullMap() {
        let row = FirebaseLeaderboardRepository.rawRow(from: [
            "rank": 3,
            "uid": "abc",
            "displayName": "Anna",
            "avatarPath": "avatars/abc.jpg",
            "value": 250,
        ])
        XCTAssertEqual(row?.rank, 3)
        XCTAssertEqual(row?.uid, "abc")
        XCTAssertEqual(row?.displayName, "Anna")
        XCTAssertEqual(row?.avatarPath, "avatars/abc.jpg")
        XCTAssertEqual(row?.value, 250)
    }

    func testRawRowDropsARowWithoutAUid() {
        XCTAssertNil(FirebaseLeaderboardRepository.rawRow(from: ["rank": 1, "value": 10]))
        XCTAssertNil(FirebaseLeaderboardRepository.rawRow(from: ["rank": 1, "uid": "", "value": 10]))
    }

    func testRawRowDropsANonMapValue() {
        XCTAssertNil(FirebaseLeaderboardRepository.rawRow(from: "not a map"))
    }

    func testRawRowDegradesMissingFieldsDefensively() {
        let row = FirebaseLeaderboardRepository.rawRow(from: ["uid": "abc"])
        XCTAssertEqual(row?.rank, 0)
        XCTAssertEqual(row?.displayName, "")
        XCTAssertNil(row?.avatarPath)
        XCTAssertEqual(row?.value, 0)
    }

    func testRawRowTreatsAnEmptyAvatarPathAsNil() {
        let row = FirebaseLeaderboardRepository.rawRow(from: ["uid": "abc", "avatarPath": ""])
        XCTAssertNil(row?.avatarPath)
    }

    func testRawCategoriesFoldsEveryCategoryAndSkipsNonArrays() {
        let map: [String: Any] = [
            "crownPoints": [
                ["rank": 1, "uid": "a", "value": 10],
                ["rank": 2, "uid": "b", "value": 5],
            ],
            "distance": "not an array",
        ]
        let raw = FirebaseLeaderboardRepository.rawCategories(fromCategoriesMap: map)
        XCTAssertEqual(raw["crownPoints"]?.map(\.uid), ["a", "b"])
        XCTAssertNil(raw["distance"])
    }

    func testRawCategoriesOfNilMapIsEmpty() {
        XCTAssertTrue(FirebaseLeaderboardRepository.rawCategories(fromCategoriesMap: nil).isEmpty)
    }

    // MARK: - season clock

    func testSeasonIdFormatsAsciiYearMonth() {
        let stockholm = TimeZone(identifier: "Europe/Stockholm")
        // 2026-08-15T12:00:00Z → 14:00 on 2026-08-15 in Stockholm (CEST, +2).
        let midAugust = Date(timeIntervalSince1970: 1_786_795_200)
        XCTAssertEqual(LeaderboardSeasonClock.seasonId(for: midAugust, zone: stockholm), "2026-08")
    }

    func testSeasonIdIsAsciiEvenUnderANonLatinLocale() {
        // The formatter pins en_US_POSIX, so the result is ASCII digits
        // regardless of the ambient locale (a device on Arabic-Indic digits
        // must still read the `2026-08` document, not a season that does not
        // exist).
        let stockholm = TimeZone(identifier: "Europe/Stockholm")
        let midAugust = Date(timeIntervalSince1970: 1_786_795_200)
        let id = LeaderboardSeasonClock.seasonId(for: midAugust, zone: stockholm)
        XCTAssertTrue(id.allSatisfy { $0.isASCII })
    }

    func testSeasonIdRespectsTheStockholmMonthBoundary() {
        let stockholm = TimeZone(identifier: "Europe/Stockholm")
        // 2026-08-31T23:30:00Z is already 2026-09-01 01:30 in Stockholm (+2),
        // so the season has rolled to September even though it is still August
        // in UTC — the client and backend must agree on the Stockholm boundary.
        let lateAugustUtc = Date(timeIntervalSince1970: 1_788_219_000)
        XCTAssertEqual(LeaderboardSeasonClock.seasonId(for: lateAugustUtc, zone: stockholm), "2026-09")
    }

    func testSeasonIdWithNilZoneUsesUTCNotTheDeviceTimeZone() {
        // A nil zone must resolve to UTC (.gmt), never silently to the device
        // time zone. 2026-08-31T23:30:00Z is still August in UTC.
        let lateAugustUtc = Date(timeIntervalSince1970: 1_788_219_000)
        XCTAssertEqual(LeaderboardSeasonClock.seasonId(for: lateAugustUtc, zone: nil), "2026-08")
    }
}
