import XCTest
@testable import KCC

final class DriveHistoryFeatureTests: XCTestCase {
    private let baseDate = Date(timeIntervalSince1970: 1_700_000_000)

    func testHistoryWireMappingIsDefensiveAndCarriesPagingPolicy() throws {
        let page = try DriveHistoryPage.fromWire([
            "tier": "plus",
            "hasMore": true,
            "nextCursorRideId": "ride-1",
            "hiddenDriveCount": 4,
            "drives": [[
                "rideId": "ride-1", "title": " Coast ", "durationSeconds": 600,
                "distanceMeters": 12_500, "createdAtMillis": 1_700_000_000_000,
                "routeThumbnail": "encoded",
            ], ["rideId": "missing-duration"]],
        ])
        XCTAssertEqual(page.tier, .plus)
        XCTAssertTrue(page.hasMore)
        XCTAssertEqual(page.hiddenDriveCount, 4)
        XCTAssertEqual(page.drives.count, 1)
        XCTAssertEqual(page.drives[0].routeThumbnail, "encoded")
    }

    func testFiltersUseHalfOpenBandsAndSortMissingValuesLast() {
        let drives = [drive("unknown", title: nil, distance: nil),
                      drive("short", title: "Coast", distance: 9_999),
                      drive("ten", title: "Coastal", distance: 10_000),
                      drive("fifty", title: "Forest", distance: 50_000)]
        let result = DriveFilters.apply(drives, criteria: DriveFilterCriteria(
            query: "coast", dateRange: .all, distanceBand: .from10To50, sort: .longest
        ), now: baseDate)
        XCTAssertEqual(result.map(\.id), ["ten"])
        let longest = DriveFilters.apply(drives, criteria: DriveFilterCriteria(sort: .longest), now: baseDate)
        XCTAssertEqual(longest.map(\.id), ["fifty", "ten", "short", "unknown"])
    }

    func testRouteDecoderReadsCanonicalFixtureAndRejectsCorruption() {
        let point = RecordedDrivePoint(latitude: 57.4, longitude: 12,
                                       timestampMilliseconds: 1_700_000_000_000)
        let decoded = DriveRouteCodec.decode(DriveRouteCodec.encode([point]))
        XCTAssertEqual(decoded, [DriveRoutePoint(latitude: 57.4, longitude: 12,
                                                  timestampMilliseconds: 1_700_000_000_000)])
        XCTAssertNil(DriveRouteCodec.decode(Data([0x43, 0x43])))
    }

    func testRouteDecoderReadsAndroidGzipEnvelope() {
        // gzip(mtime: 0) containing an empty canonical CCRB v1 route.
        let gzip = Data([
            0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xff,
            0x73, 0x76, 0x0e, 0x72, 0x62, 0x64, 0x60, 0x00, 0x00, 0x72,
            0xae, 0x4f, 0x13, 0x07, 0x00, 0x00, 0x00,
        ])
        XCTAssertEqual(DriveRouteCodec.decode(gzip), [])
    }

    func testDistanceMarkersIgnoreImplausibleGpsJumpAndInterpolateKilometres() {
        let points = [
            DriveRoutePoint(latitude: 57, longitude: 12, timestampMilliseconds: 0),
            DriveRoutePoint(latitude: 57.018, longitude: 12, timestampMilliseconds: 120_000),
            DriveRoutePoint(latitude: 60, longitude: 12, timestampMilliseconds: 122_000),
        ]
        let markers = DriveRouteDistanceMarkers.markers(for: points)
        XCTAssertEqual(markers.map(\.kilometer), [1, 2])
        XCTAssertEqual(markers[0].longitude, 12, accuracy: 0.000_001)
    }

    func testShareSummaryNeverIncludesTitleDateOrRouteCoordinates() {
        let item = drive("private", title: "Home to work", distance: 12_345)
        let text = DriveShareText.summary(for: item)
        XCTAssertFalse(text.contains("Home to work"))
        XCTAssertFalse(text.contains("57."))
        XCTAssertFalse(text.contains("12.0"))
        XCTAssertTrue(text.contains("12.3 km"))
    }

    @MainActor
    func testCoordinatorDeduplicatesAppendedPageAndReloadsAfterDelete() async {
        let repository = FakeRepository()
        repository.pages = [
            DriveHistoryPage(tier: .supporter, drives: [drive("a")], hasMore: true,
                             nextCursorRideId: "a", hiddenDriveCount: 0),
            DriveHistoryPage(tier: .supporter, drives: [drive("a"), drive("b")], hasMore: false,
                             nextCursorRideId: nil, hiddenDriveCount: 0),
            DriveHistoryPage(tier: .supporter, drives: [drive("b")], hasMore: false,
                             nextCursorRideId: nil, hiddenDriveCount: 0),
        ]
        let coordinator = DriveHistoryCoordinator(repository: repository)
        await coordinator.load()
        await coordinator.loadMore()
        XCTAssertEqual(coordinator.drives.map(\.id), ["a", "b"])
        await coordinator.delete(coordinator.drives[0])
        XCTAssertEqual(repository.deleted, ["a"])
        XCTAssertEqual(coordinator.drives.map(\.id), ["b"])
    }

    private func drive(
        _ id: String, title: String? = "Drive", distance: Double? = 1_000
    ) -> SavedDrive {
        SavedDrive(id: id, title: title, distanceMeters: distance, durationSeconds: 600,
                   averageSpeedMetersPerSecond: distance.map { $0 / 600 },
                   startedAt: baseDate, endedAt: baseDate.addingTimeInterval(600),
                   createdAt: baseDate, maxSpeedMetersPerSecond: 20,
                   carImagePath: nil, convoyMembers: [])
    }
}

private final class FakeRepository: DriveHistoryRepository, @unchecked Sendable {
    var pages: [DriveHistoryPage] = []
    var deleted: [String] = []

    func listHistory(cursorRideId: String?, pageSize: Int) async throws -> DriveHistoryPage {
        pages.removeFirst()
    }
    func fetchStats(monthStart: Date, monthEnd: Date) async throws -> DriveStatsSnapshot {
        DriveStatsSnapshot(tier: .supporter, totalDrives: 1, totalDistanceMeters: 1_000,
                           totalDurationSeconds: 60, longestDriveMeters: 1_000,
                           averageDriveMeters: 1_000, fastestAverageSpeedMetersPerSecond: nil,
                           highestMaxSpeedMetersPerSecond: nil, thisMonthDrives: 1,
                           thisMonthDistanceMeters: 1_000)
    }
    func deleteDrive(rideId: String) async throws { deleted.append(rideId) }
    func loadRoute(rideId: String) async -> DriveRouteReplayState { .unavailable }
    func imageDownloadURL(for imagePath: String) async -> URL? { nil }
}
