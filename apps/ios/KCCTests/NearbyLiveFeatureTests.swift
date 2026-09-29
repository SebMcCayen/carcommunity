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

        await coordinator.poll(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        for _ in 0..<10 where repository.observedUids.count < maximumNearbyLiveMarkers { await Task.yield() }

        XCTAssertEqual(coordinator.orderedUids.count, maximumNearbyLiveMarkers)
        XCTAssertFalse(coordinator.orderedUids.contains("me"))
        XCTAssertFalse(coordinator.orderedUids.contains("u1"))
        XCTAssertEqual(Set(repository.observedUids), Set(coordinator.orderedUids))
    }

    func testFailedRefreshRetainsLastGoodRoster() async {
        let repository = FakeRepository()
        repository.nearby = [NearbyLiveSession(uid: "u1", latitude: 57, longitude: 12, displayName: "A")]
        repository.events["u1"] = [.value(marker("u1"))]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: repository, currentUid: "me", excludedUids: [])
        await coordinator.poll(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        for _ in 0..<10 where coordinator.positions["u1"] == nil { await Task.yield() }
        repository.error = TestError.failed

        await coordinator.poll(
            center: MapPoint(longitude: 13, latitude: 58),
            radiusMeters: 15_000,
            minimumInterval: .zero
        )

        XCTAssertEqual(coordinator.orderedUids, ["u1"])
        XCTAssertEqual(coordinator.visibleMarkers().map(\.uid), ["u1"])
        XCTAssertTrue(coordinator.lastRefreshFailed)
    }

    func testDiscoveryCoordinatesNeverRenderAcrossNoValueRetryHideBlockAndStop() async {
        let repository = FakeRepository()
        repository.nearby = ["live", "never", "retry", "hidden", "stopped", "blocked"].map {
            NearbyLiveSession(uid: $0, latitude: 57, longitude: 12, displayName: "Discovery only")
        }
        repository.events["live"] = [.value(marker("live"))]
        repository.neverYieldUids = ["never"]
        repository.events["retry"] = [.retry]
        repository.events["hidden"] = [.value(nil)]
        repository.events["stopped"] = [.value(nil)]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: repository, currentUid: "me", excludedUids: ["blocked"])

        await coordinator.poll(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        for _ in 0..<20 where coordinator.positions["live"] == nil { await Task.yield() }

        XCTAssertEqual(Set(coordinator.positions.keys), ["live"])
        XCTAssertEqual(coordinator.visibleMarkers().map(\.uid), ["live"])
        XCTAssertFalse(repository.observedUids.contains("blocked"))

        // Force a fresh authorization roster, then make the previously live
        // member's replacement stream fail before a value. The old confirmed
        // coordinate must not cross the subscription boundary.
        repository.events["live"] = [.retry]
        repository.nearby.append(NearbyLiveSession(
            uid: "new-never", latitude: 58, longitude: 13, displayName: nil
        ))
        repository.neverYieldUids.insert("new-never")
        await coordinator.poll(
            center: MapPoint(longitude: 13, latitude: 58),
            radiusMeters: 15_000,
            minimumInterval: .zero
        )
        XCTAssertEqual(coordinator.positions, [:])

        // A new block removes the uid/listener immediately. The never-yielding
        // and retrying streams cannot fall back to callable coordinates, and
        // hide/stop nil values remain absent.
        coordinator.activate(
            repository: repository,
            currentUid: "me",
            excludedUids: ["blocked", "live"]
        )
        XCTAssertEqual(coordinator.positions, [:])
        XCTAssertEqual(coordinator.visibleMarkers(), [])

        coordinator.deactivate()
        XCTAssertEqual(coordinator.orderedUids, [])
        XCTAssertEqual(coordinator.positions, [:])
    }

    func testDeactivateCancelsAndClearsAllPublicMapState() async {
        let repository = FakeRepository()
        repository.nearby = [NearbyLiveSession(uid: "u1", latitude: 57, longitude: 12, displayName: nil)]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: repository, currentUid: "me", excludedUids: [])
        await coordinator.poll(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)

        coordinator.deactivate()

        XCTAssertEqual(coordinator.orderedUids, [])
        XCTAssertEqual(coordinator.positions, [:])
        XCTAssertEqual(coordinator.imageURLs, [:])
    }

    func testUserSwitchWithSameRosterClearsOldMarkerAndReopensStream() async {
        let repository = FakeRepository()
        repository.nearby = [NearbyLiveSession(
            uid: "nearby", latitude: 57, longitude: 12, displayName: nil
        )]
        repository.controlledUids = ["nearby"]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: repository, currentUid: "viewer-a", excludedUids: [])
        await coordinator.poll(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        await wait { repository.openCounts["nearby"] == 1 }
        repository.emit(.value(marker("nearby")), uid: "nearby")
        await wait { coordinator.positions["nearby"] != nil }

        coordinator.activate(repository: repository, currentUid: "viewer-b", excludedUids: [])

        XCTAssertEqual(coordinator.positions, [:])
        XCTAssertEqual(coordinator.orderedUids, [])
        XCTAssertEqual(repository.openCounts["nearby"], 1)
        await wait { repository.terminationCounts["nearby", default: 0] >= 1 }

        await coordinator.poll(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        await wait { repository.openCounts["nearby"] == 2 }
        XCTAssertEqual(coordinator.positions, [:])
        repository.emit(.value(marker("nearby")), uid: "nearby")
        await wait { coordinator.positions["nearby"] != nil }
    }

    func testRepositoryReplacementWithSameUserAndRosterReopensAuthorizationStream() async {
        let firstRepository = FakeRepository()
        firstRepository.nearby = [NearbyLiveSession(
            uid: "nearby", latitude: 57, longitude: 12, displayName: nil
        )]
        firstRepository.controlledUids = ["nearby"]
        let replacementRepository = FakeRepository()
        replacementRepository.nearby = [NearbyLiveSession(
            uid: "nearby", latitude: 57, longitude: 12, displayName: nil
        )]
        replacementRepository.controlledUids = ["nearby"]
        let coordinator = NearbyLiveCoordinator()
        coordinator.activate(repository: firstRepository, currentUid: "viewer", excludedUids: [])
        await coordinator.poll(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        await wait { firstRepository.openCounts["nearby"] == 1 }
        firstRepository.emit(.value(marker("nearby")), uid: "nearby")
        await wait { coordinator.positions["nearby"] != nil }

        coordinator.activate(
            repository: replacementRepository,
            currentUid: "viewer",
            excludedUids: []
        )

        XCTAssertEqual(coordinator.positions, [:])
        XCTAssertEqual(coordinator.orderedUids, [])
        XCTAssertNil(replacementRepository.openCounts["nearby"])
        await wait { firstRepository.terminationCounts["nearby", default: 0] >= 1 }

        await coordinator.poll(center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 15_000)
        await wait { replacementRepository.openCounts["nearby"] == 1 }
        XCTAssertEqual(coordinator.positions, [:])
        replacementRepository.emit(.value(marker("nearby")), uid: "nearby")
        await wait { coordinator.positions["nearby"] != nil }
    }

    func testRadiusClampsInvalidAndExtremeValues() {
        XCTAssertEqual(NearbyLiveRadius.clamp(.nan), defaultNearbyLiveRadiusMeters)
        XCTAssertEqual(NearbyLiveRadius.clamp(1), 100)
        XCTAssertEqual(NearbyLiveRadius.clamp(100_000), 50_000)
    }

    func testRapidCameraChangesKeepTwentySecondCadenceAndUseLatestBounds() async {
        var now = ContinuousClock.now
        let repository = FakeRepository()
        let coordinator = NearbyLiveCoordinator(monotonicNow: { now })
        coordinator.activate(repository: repository, currentUid: "me", excludedUids: [])

        let firstPollStarted = await coordinator.poll(
            center: MapPoint(longitude: 12, latitude: 57), radiusMeters: 1_000
        )
        XCTAssertTrue(firstPollStarted)
        now = now.advanced(by: .seconds(1))
        let rapidPollStarted = await coordinator.poll(
            center: MapPoint(longitude: 13, latitude: 58), radiusMeters: 2_000
        )
        XCTAssertFalse(rapidPollStarted)
        now = now.advanced(by: .seconds(19))
        let latestPollStarted = await coordinator.poll(
            center: MapPoint(longitude: 14, latitude: 59), radiusMeters: 3_000
        )
        XCTAssertTrue(latestPollStarted)

        XCTAssertEqual(repository.listCenters.map(\.longitude), [12, 14])
        XCTAssertEqual(repository.listRadii, [1_000, 3_000])
    }

    private func marker(_ uid: String) -> LiveMarker {
        LiveMarker(
            uid: uid, latitude: 57.48, longitude: 12.07,
            displayName: nil, imagePath: nil, recordedAt: Date(), accuracyMeters: nil
        )
    }

    private func wait(
        iterations: Int = 100,
        until condition: @escaping @MainActor () -> Bool
    ) async {
        for _ in 0..<iterations {
            if condition() { return }
            await Task.yield()
        }
        XCTFail("Condition did not become true")
    }

    private enum TestError: Error { case failed }

    private final class FakeRepository: LiveLocationRepository, @unchecked Sendable {
        var nearby: [NearbyLiveSession] = []
        var error: Error?
        var observedUids: [String] = []
        var events: [String: [LiveMarkerUpdateEvent]] = [:]
        var neverYieldUids = Set<String>()
        var controlledUids = Set<String>()
        var heldContinuations: [AsyncStream<LiveMarkerUpdateEvent>.Continuation] = []
        var controlledContinuations: [String: AsyncStream<LiveMarkerUpdateEvent>.Continuation] = [:]
        var openCounts: [String: Int] = [:]
        var terminationCounts: [String: Int] = [:]
        var listCenters: [MapPoint] = []
        var listRadii: [Double] = []

        func startSession(duration: LiveSessionDuration, vehicleId: String?) async throws {}
        func updatePosition(_ coordinate: LiveCoordinate) async throws {}
        func stopSession() async throws {}
        func hideMeNow() async throws {}
        func currentUserId() -> String? { "me" }
        func ownSessionUpdates(uid: String) -> AsyncStream<LiveSessionInfo?> { AsyncStream { $0.finish() } }
        func latestUpdates(uid: String) -> AsyncStream<LiveMarker?> { AsyncStream { $0.finish() } }
        func latestUpdateEvents(uid: String) -> AsyncStream<LiveMarkerUpdateEvent> {
            observedUids.append(uid)
            if controlledUids.contains(uid) {
                openCounts[uid, default: 0] += 1
                return AsyncStream { continuation in
                    controlledContinuations[uid] = continuation
                    continuation.onTermination = { [weak self] _ in
                        self?.terminationCounts[uid, default: 0] += 1
                    }
                }
            }
            if neverYieldUids.contains(uid) {
                return AsyncStream { heldContinuations.append($0) }
            }
            let values = events[uid] ?? []
            return AsyncStream { continuation in
                values.forEach { continuation.yield($0) }
                continuation.finish()
            }
        }
        func emit(_ event: LiveMarkerUpdateEvent, uid: String) {
            controlledContinuations[uid]?.yield(event)
        }
        func imageDownloadURL(for imagePath: String) async -> URL? { nil }
        func listNearby(center: MapPoint, radiusMeters: Double) async throws -> [NearbyLiveSession] {
            listCenters.append(center)
            listRadii.append(radiusMeters)
            if let error { throw error }
            return nearby
        }
    }
}
