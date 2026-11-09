import XCTest
@testable import KCC

@MainActor
final class NearbyWaveTests: XCTestCase {
    private let origin = MapPoint(longitude: 12, latitude: 57)

    func testOnlyConfirmedMarkersWithinRangeCanShowTheButton() async {
        let range = NearbyWaveRange(
            origin: origin,
            markers: [marker("near"), marker("moved", latitude: 57.2)],
            discovery: [
                NearbyLiveSession(uid: "moved", latitude: 57, longitude: 12, displayName: nil),
                NearbyLiveSession(uid: "offscreen", latitude: 57, longitude: 12, displayName: nil),
            ]
        )
        XCTAssertEqual(range.markerUids, ["near"])
        XCTAssertEqual(range.nearbyUids, ["near", "offscreen"])
    }

    func testSuccessfulSendMarksDiscoveryRosterUntilADriverLeavesRange() async {
        let coordinator = NearbyWaveCoordinator(repository: StubRepository(result: .sent))
        let now = Date(timeIntervalSince1970: 10_000)
        let initial = NearbyWaveRange(
            origin: origin,
            markers: [marker("near")],
            discovery: [NearbyLiveSession(uid: "offscreen", latitude: 57, longitude: 12, displayName: nil)]
        )

        XCTAssertTrue(coordinator.canOffer(in: initial))
        await coordinator.send(in: initial, now: now)
        XCTAssertEqual(coordinator.cooldownUntil, now.addingTimeInterval(45))
        XCTAssertFalse(coordinator.canOffer(in: initial))

        let bothVisible = range(with: [marker("near"), marker("offscreen")])
        coordinator.reconcile(bothVisible)
        XCTAssertFalse(coordinator.canOffer(in: bothVisible))

        coordinator.reconcile(range(with: [marker("near"), marker("offscreen", latitude: 57.2)]))
        coordinator.reconcile(bothVisible)
        XCTAssertTrue(coordinator.canOffer(in: bothVisible))
    }

    func testFailedSendAllowsRetryWithoutMarkingTheDriver() async {
        let coordinator = NearbyWaveCoordinator(repository: StubRepository(result: .failed))
        let range = range(with: [marker("near")])

        await coordinator.send(in: range)

        XCTAssertTrue(coordinator.canOffer(in: range))
        XCTAssertEqual(coordinator.cooldownUntil, .distantPast)
        XCTAssertNil(coordinator.pop)
    }

    func testServerCooldownKeepsTheSameDriverEligible() async {
        let coordinator = NearbyWaveCoordinator(repository: StubRepository(result: .rateLimited(
            retryAfterMilliseconds: 12_000
        )))
        let range = range(with: [marker("near")])
        let before = Date()

        await coordinator.send(in: range)

        XCTAssertTrue(coordinator.canOffer(in: range))
        XCTAssertGreaterThanOrEqual(coordinator.cooldownUntil, before.addingTimeInterval(12))
        XCTAssertLessThanOrEqual(coordinator.cooldownUntil, Date().addingTimeInterval(12))
        XCTAssertNil(coordinator.pop)
    }

    func testIncomingWaveShowsTheSendersNameAndClearsWhenSharingEnds() async {
        let event = NearbyWaveEvent(id: "wave", senderName: "Ada")
        let coordinator = NearbyWaveCoordinator(repository: StubRepository(result: .sent, events: [event]))

        await coordinator.observeInbox(uid: "me")
        XCTAssertEqual(coordinator.pop, .received(event))

        coordinator.sharingEnded()
        XCTAssertNil(coordinator.pop)
    }

    private func range(with markers: [LiveMarker]) -> NearbyWaveRange {
        NearbyWaveRange(origin: origin, markers: markers, discovery: [])
    }

    private func marker(_ uid: String, latitude: Double = 57) -> LiveMarker {
        LiveMarker(
            uid: uid, latitude: latitude, longitude: 12, displayName: nil,
            imagePath: nil, recordedAt: nil, accuracyMeters: nil
        )
    }
}

private struct StubRepository: NearbyWaveRepository {
    let result: NearbyWaveSendResult
    var events: [NearbyWaveEvent] = []

    func send(clientId: String) async -> NearbyWaveSendResult { result }

    func incoming(uid: String, since: Date) -> AsyncStream<NearbyWaveEvent> {
        AsyncStream { continuation in
            for event in events { continuation.yield(event) }
            continuation.finish()
        }
    }
}
