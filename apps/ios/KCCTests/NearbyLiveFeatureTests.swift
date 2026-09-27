import XCTest
@testable import KCC

final class NearbyLiveParserTests: XCTestCase {
    func testParsesValidRowsAndDropsMalformedCoordinates() {
        let parsed = NearbyLiveParser.parse([
            "sessions": [
                ["uid": " good ", "latitude": 57.48, "longitude": 12.07, "displayName": " Ada "],
                ["uid": "bad-lat", "latitude": 91, "longitude": 12],
                ["uid": "bad-lng", "latitude": 57, "longitude": Double.infinity],
                ["latitude": 57, "longitude": 12],
                "not-a-row",
            ]
        ])
        XCTAssertEqual(parsed, [NearbyLiveSession(
            uid: "good", latitude: 57.48, longitude: 12.07, displayName: "Ada"
        )])
    }

    func testShapelessPayloadIsEmpty() {
        XCTAssertEqual(NearbyLiveParser.parse(nil), [])
        XCTAssertEqual(NearbyLiveParser.parse(["sessions": "wrong"]), [])
    }
}

final class NearbyLivePresentationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 10_000)

    func testFiltersOwnConvoyStaleAndMismatchedMarkersWithoutChangingOrder() {
        let positions = [
            "me": marker("me"), "near": marker("near"), "convoy": marker("convoy"),
            "stale": marker("stale", at: Date(timeIntervalSince1970: 9_000)),
            "mismatch": marker("different"),
        ]
        let result = NearbyLivePresentation.markers(
            orderedUids: ["me", "near", "convoy", "near", "stale", "mismatch"],
            positions: positions, currentUid: "me", excludedUids: ["convoy"], now: now
        )
        XCTAssertEqual(result.map(\.uid), ["near"])
    }

    func testUnknownTimestampRemainsVisible() {
        XCTAssertEqual(NearbyLivePresentation.markers(
            orderedUids: ["near"], positions: ["near": marker("near", at: nil)],
            currentUid: "me", excludedUids: [], now: now
        ).map(\.uid), ["near"])
    }

    func testStalenessBoundaryIsInclusive() {
        XCTAssertEqual(NearbyLivePresentation.markers(
            orderedUids: ["near"],
            positions: ["near": marker("near", at: now.addingTimeInterval(-240))],
            currentUid: nil, excludedUids: [], now: now
        ).count, 1)
    }

    private func marker(_ uid: String, at date: Date? = Date(timeIntervalSince1970: 10_000)) -> LiveMarker {
        LiveMarker(uid: uid, latitude: 57.48, longitude: 12.07, displayName: nil,
                   imagePath: nil, recordedAt: date, accuracyMeters: nil)
    }
}

@MainActor
final class NearbyLiveCoordinatorTests: XCTestCase {
    func testRefreshFiltersSelfAndConvoyAndCapsPerUidListeners() async {
        let repository = FakeRepository()
        repository.nearby = (0..<55).map {
            NearbyLiveSession(uid: "u\($0)", latitude: 57, longitude: 12, displayName: nil)
        } + [NearbyLiveSession(uid: "me", latitude: 57, longitude: 12, displayName: nil)]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: repository, currentUid: "me", excludedUids: ["u1"])

        await coordinator.refresh(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        for _ in 0..<10 where repository.observedUids.count < maximumNearbyLiveMarkers { await Task.yield() }

        XCTAssertEqual(coordinator.orderedUids.count, maximumNearbyLiveMarkers)
        XCTAssertFalse(coordinator.orderedUids.contains("me"))
        XCTAssertFalse(coordinator.orderedUids.contains("u1"))
        XCTAssertEqual(Set(repository.observedUids), Set(coordinator.orderedUids))
    }

    func testFailedRefreshRetainsLastGoodRoster() async {
        let repository = FakeRepository()
        repository.nearby = [NearbyLiveSession(uid: "u1", latitude: 57, longitude: 12, displayName: "A")]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: repository, currentUid: "me", excludedUids: [])
        await coordinator.refresh(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        repository.error = TestError.failed

        await coordinator.refresh(center: MapPoint(longitude: 13, latitude: 58), radiusMeters: 15_000)

        XCTAssertEqual(coordinator.orderedUids, ["u1"])
        XCTAssertEqual(coordinator.visibleMarkers().map(\.uid), ["u1"])
        XCTAssertTrue(coordinator.lastRefreshFailed)
    }

    func testDeniedOrHiddenLatestValueRemovesSeed() async {
        let repository = FakeRepository()
        repository.nearby = [NearbyLiveSession(uid: "u1", latitude: 57, longitude: 12, displayName: nil)]
        repository.events["u1"] = [.value(nil)]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: repository, currentUid: "me", excludedUids: [])

        await coordinator.refresh(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        for _ in 0..<10 where coordinator.positions["u1"] != nil { await Task.yield() }

        XCTAssertNil(coordinator.positions["u1"])
        XCTAssertEqual(coordinator.visibleMarkers(), [])
    }

    func testDeactivateCancelsAndClearsAllPublicMapState() async {
        let repository = FakeRepository()
        repository.nearby = [NearbyLiveSession(uid: "u1", latitude: 57, longitude: 12, displayName: nil)]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: repository, currentUid: "me", excludedUids: [])
        await coordinator.refresh(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)

        coordinator.deactivate()

        XCTAssertEqual(coordinator.orderedUids, [])
        XCTAssertEqual(coordinator.positions, [:])
        XCTAssertEqual(coordinator.imageURLs, [:])
    }

    func testRadiusClampsInvalidAndExtremeValues() {
        XCTAssertEqual(NearbyLiveRadius.clamp(.nan), defaultNearbyLiveRadiusMeters)
        XCTAssertEqual(NearbyLiveRadius.clamp(1), 100)
        XCTAssertEqual(NearbyLiveRadius.clamp(100_000), 50_000)
    }

    private enum TestError: Error { case failed }

    private final class FakeRepository: LiveLocationRepository, @unchecked Sendable {
        var nearby: [NearbyLiveSession] = []
        var error: Error?
        var observedUids: [String] = []
        var events: [String: [LiveMarkerUpdateEvent]] = [:]

        func startSession(duration: LiveSessionDuration, vehicleId: String?) async throws {}
        func updatePosition(_ coordinate: LiveCoordinate) async throws {}
        func stopSession() async throws {}
        func hideMeNow() async throws {}
        func currentUserId() -> String? { "me" }
        func ownSessionUpdates(uid: String) -> AsyncStream<LiveSessionInfo?> { AsyncStream { $0.finish() } }
        func latestUpdates(uid: String) -> AsyncStream<LiveMarker?> { AsyncStream { $0.finish() } }
        func latestUpdateEvents(uid: String) -> AsyncStream<LiveMarkerUpdateEvent> {
            observedUids.append(uid)
            let values = events[uid] ?? []
            return AsyncStream { continuation in
                values.forEach { continuation.yield($0) }
                continuation.finish()
            }
        }
        func imageDownloadURL(for imagePath: String) async -> URL? { nil }
        func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [NearbyLiveSession] {
            if let error { throw error }
            return nearby
        }
    }
}
