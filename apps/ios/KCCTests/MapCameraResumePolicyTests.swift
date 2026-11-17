import XCTest
@testable import KCC

final class MapCameraResumePolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testOneReturnPerResumeWaitsForFreshFixAndIgnoresCachedReplay() {
        var policy = MapCameraResumePolicy()
        policy.visibilityChanged(visible: true, following: true)
        XCTAssertFalse(policy.takeReturn(fixAt: now.addingTimeInterval(-6), now: now))
        XCTAssertTrue(policy.pending)
        XCTAssertTrue(policy.takeReturn(fixAt: now, now: now))
        XCTAssertFalse(policy.takeReturn(fixAt: now, now: now))
        policy.visibilityChanged(visible: false, following: true)
        policy.visibilityChanged(visible: true, following: true)
        XCTAssertFalse(policy.takeReturn(fixAt: now, now: now))
        XCTAssertTrue(policy.takeReturn(fixAt: now.addingTimeInterval(1), now: now.addingTimeInterval(1)))
    }

    func testManualIntentNeverArmsOrRestartsAnAutomaticReturn() {
        var policy = MapCameraResumePolicy()
        policy.visibilityChanged(visible: true, following: false)
        XCTAssertFalse(policy.pending)
        policy.requestReturn()
        policy.cancel()
        XCTAssertFalse(policy.takeReturn(fixAt: now, now: now))
        policy.visibilityChanged(visible: true, following: true)
        XCTAssertFalse(policy.pending)
    }

    func testCurrentLocationIsRequiredAndStationaryCourseIsHeld() {
        XCTAssertFalse(MapCameraResumePolicy.isFresh(fixAt: now.addingTimeInterval(1), now: now))
        XCTAssertEqual(MapCameraResumePolicy.course(heading: 180, speed: 0, holding: 15), 15)
        XCTAssertEqual(MapCameraResumePolicy.course(heading: 180, speed: nil, holding: 15), 15)
        XCTAssertEqual(MapCameraResumePolicy.course(heading: .nan, speed: 10, holding: 15), 15)
        XCTAssertEqual(MapCameraResumePolicy.course(heading: 180, speed: 10, holding: 15), 180)
        XCTAssertEqual(MapCameraResumePolicy.course(heading: 180, speed: 4, speedAccuracy: 5, holding: 15), 15)
    }

    func testNorthCrossingUsesTheShortArc() {
        XCTAssertEqual(MapCameraResumePolicy.shortestBearing(from: 359, to: 1), 361)
        XCTAssertEqual(MapCameraResumePolicy.shortestBearing(from: 1, to: 359), -1)
    }
}
