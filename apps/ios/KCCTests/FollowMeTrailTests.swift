import Foundation
import XCTest
@testable import KCC

final class FollowMeTrailTests: XCTestCase {
    func testCCRBEncodingMatchesAndroidFixtureAndRoundTrips() {
        let points = [
            MapPoint(longitude: 12.0000, latitude: 57.0000),
            MapPoint(longitude: 12.0010, latitude: 57.0005),
            MapPoint(longitude: 12.0025, latitude: 57.0012)
        ]

        let encoded = FollowMeTrail.encode(points)

        XCTAssertEqual(encoded, "Q0NSQgEAA8DmtwWAvpIBAGTIAQGMAawCAQ==")
        let decoded = FollowMeTrail.decode(encoded)
        XCTAssertEqual(decoded.count, points.count)
        for (actual, expected) in zip(decoded, points) {
            XCTAssertEqual(actual.latitude, expected.latitude, accuracy: 0.00001)
            XCTAssertEqual(actual.longitude, expected.longitude, accuracy: 0.00001)
        }
    }

    func testDecodeFailsClosedForBlankCorruptAndOutOfRangeInput() {
        XCTAssertEqual(FollowMeTrail.decode(nil), [])
        XCTAssertEqual(FollowMeTrail.decode(""), [])
        XCTAssertEqual(FollowMeTrail.decode("not base64"), [])
        XCTAssertEqual(FollowMeTrail.decode("Zm9vYmFy"), [])
    }

    func testVisibilityRequiresOtherFreshAcceptedLeader() {
        let now = Date(timeIntervalSince1970: 1_000)
        XCTAssertTrue(FollowMeTrail.shouldDraw(
            leaderUid: "leader",
            selfUid: "me",
            leaderIsMember: true,
            lastFreshAt: now.addingTimeInterval(-1),
            now: now
        ))
        XCTAssertFalse(FollowMeTrail.shouldDraw(
            leaderUid: "me",
            selfUid: "me",
            leaderIsMember: true,
            lastFreshAt: now,
            now: now
        ))
        XCTAssertFalse(FollowMeTrail.shouldDraw(
            leaderUid: "leader",
            selfUid: "me",
            leaderIsMember: false,
            lastFreshAt: now,
            now: now
        ))
        XCTAssertFalse(FollowMeTrail.shouldDraw(
            leaderUid: "leader",
            selfUid: "me",
            leaderIsMember: true,
            lastFreshAt: now.addingTimeInterval(-FollowMeTrail.staleAfter),
            now: now
        ))
    }

    func testPublisherThrottlesJitterAndKeepsRollingFifteenKilometers() {
        var publisher = FollowMeTrailPublisher(throttle: 4)
        let epoch = Date(timeIntervalSince1970: 0)
        XCTAssertNotNil(publisher.ingest(
            MapPoint(longitude: 12, latitude: 57),
            now: epoch
        ))
        XCTAssertNil(publisher.ingest(
            MapPoint(longitude: 12.002, latitude: 57),
            now: epoch.addingTimeInterval(1)
        ))
        XCTAssertNotNil(publisher.ingest(
            MapPoint(longitude: 12.004, latitude: 57),
            now: epoch.addingTimeInterval(5)
        ))
        XCTAssertNil(publisher.ingest(
            MapPoint(longitude: 12.0040001, latitude: 57),
            now: epoch.addingTimeInterval(20)
        ))

        publisher.reset()
        var latitude = 57.0
        for index in 0..<130 {
            latitude += 0.0018
            _ = publisher.ingest(
                MapPoint(longitude: 12, latitude: latitude),
                now: epoch.addingTimeInterval(Double(index * 5))
            )
        }
        XCTAssertTrue(FollowMeTrailPublisher.length(publisher.points) >= 15_000)
        XCTAssertTrue(FollowMeTrailPublisher.length(publisher.points) < 15_600)
    }
}

@MainActor
final class ConvoyFollowMeCoordinatorTests: XCTestCase {
    func testOtherFreshMemberTrailRendersAndStopClearsAndCancels() async {
        let clock = FollowMeTestClock(date: Date(timeIntervalSince1970: 1_000))
        let repository = ConvoyFollowMeRepositoryFake()
        let coordinator = ConvoyFollowMeCoordinator(repository: repository, now: { clock.date })
        let surface = StubMapSurface(initialState: .loaded, autoLoad: false)
        let convoy = makeConvoy()
        let points = [
            MapPoint(longitude: 12, latitude: 57),
            MapPoint(longitude: 12.001, latitude: 57.001)
        ]

        coordinator.sync(convoy: convoy, currentUid: "me", positions: [:], surface: surface)
        repository.emit(ConvoyFollowMeState(
            leaderUid: "leader",
            polyline: FollowMeTrail.encode(points),
            updatedAt: clock.date
        ), convoyId: convoy.convoyId)
        await Task.yield()

        XCTAssertEqual(surface.followMeTrail, points)
        XCTAssertFalse(coordinator.isLeading)
        coordinator.stop()
        await Task.yield()
        XCTAssertNil(surface.followMeTrail)
        XCTAssertEqual(repository.terminationCount, 1)
    }

    func testSelfLeaderPublishesAndToggleUsesCallable() async {
        let clock = FollowMeTestClock(date: Date(timeIntervalSince1970: 1_000))
        let repository = ConvoyFollowMeRepositoryFake()
        let coordinator = ConvoyFollowMeCoordinator(repository: repository, now: { clock.date })
        let surface = StubMapSurface(initialState: .loaded, autoLoad: false)
        let convoy = makeConvoy()

        coordinator.sync(convoy: convoy, currentUid: "me", positions: [:], surface: surface)
        repository.emit(ConvoyFollowMeState(
            leaderUid: "me",
            polyline: "",
            updatedAt: clock.date
        ), convoyId: convoy.convoyId)
        await Task.yield()
        coordinator.sync(
            convoy: convoy,
            currentUid: "me",
            positions: ["me": ConvoyMemberPosition(
                uid: "me",
                latitude: 57,
                longitude: 12,
                updatedAt: clock.date
            )],
            surface: surface
        )
        await Task.yield()

        XCTAssertTrue(coordinator.isLeading)
        XCTAssertEqual(repository.writes.count, 1)
        repository.nextLeading = false
        let leading = await coordinator.setLeading(false)
        XCTAssertEqual(leading, false)
        XCTAssertEqual(repository.toggles, [FollowMeToggle(convoyId: convoy.convoyId, active: false)])
    }

    private func makeConvoy() -> ConvoyItem {
        ConvoyItem(
            convoyId: "convoy",
            title: nil,
            status: .active,
            members: [
                ConvoyMember(uid: "me", displayName: nil, role: .member, inviteStatus: .accepted),
                ConvoyMember(uid: "leader", displayName: nil, role: .owner, inviteStatus: .accepted)
            ],
            viewer: ConvoyViewer(inviteStatus: .accepted, role: .member),
            createdAt: nil
        )
    }
}

private final class FollowMeTestClock: @unchecked Sendable {
    var date: Date
    init(date: Date) { self.date = date }
}

private struct FollowMeToggle: Equatable {
    let convoyId: String
    let active: Bool
}

private struct FollowMeWrite: Equatable {
    let convoyId: String
    let polyline: String
}

private final class ConvoyFollowMeRepositoryFake: ConvoyFollowMeRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [String: AsyncStream<ConvoyFollowMeState?>.Continuation] = [:]
    private(set) var toggles: [FollowMeToggle] = []
    private(set) var writes: [FollowMeWrite] = []
    private(set) var terminationCount = 0
    var nextLeading: Bool? = true

    func setFollowMe(convoyId: String, active: Bool) async -> Bool? {
        lock.withLock {
            toggles.append(FollowMeToggle(convoyId: convoyId, active: active))
            return nextLeading
        }
    }

    func writeTrail(convoyId: String, polyline: String) async -> Bool {
        lock.withLock {
            writes.append(FollowMeWrite(convoyId: convoyId, polyline: polyline))
        }
        return true
    }

    func states(convoyId: String) -> AsyncStream<ConvoyFollowMeState?> {
        AsyncStream { continuation in
            lock.withLock { continuations[convoyId] = continuation }
            continuation.onTermination = { [weak self] _ in
                self?.lock.withLock {
                    self?.continuations[convoyId] = nil
                    self?.terminationCount += 1
                }
            }
        }
    }

    func emit(_ state: ConvoyFollowMeState?, convoyId: String) {
        lock.withLock { continuations[convoyId] }?.yield(state)
    }
}
