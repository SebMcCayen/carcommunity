import XCTest
@testable import KCC

final class MapPresencePolicyTests: XCTestCase {
    func testRefreshDoesNotRestartOrKeepAnIdleTicker() {
        var policy = MapPresencePolicy()
        policy.sync(visible: ["a", "b"], allowed: ["a", "b"], now: 0)
        policy.sync(visible: ["b", "a"], allowed: ["a", "b"], now: 0.1)
        XCTAssertEqual(policy.frames(at: 0.1)[0].alpha, 0.5, accuracy: 0.001)
        XCTAssertEqual(policy.frames(at: 0.2)[0].alpha, 1)
        XCTAssertFalse(policy.isAnimating(at: 0.2))
    }
    func testExitStopsInteractionImmediatelyThenExpires() {
        var policy = MapPresencePolicy()
        policy.sync(visible: ["a"], allowed: ["a"], now: 0)
        policy.sync(visible: [], allowed: ["a"], now: 0.2)
        XCTAssertFalse(policy.frames(at: 0.2)[0].interactive)
        XCTAssertEqual(policy.frames(at: 0.3)[0].alpha, 0.5, accuracy: 0.001)
        XCTAssertTrue(policy.frames(at: 0.41).isEmpty)
    }
    func testPrivacyAndExplicitClearRetainNoSnapshot() {
        var policy = MapPresencePolicy()
        policy.sync(visible: ["private"], allowed: ["private"], now: 0)
        policy.sync(visible: [], allowed: [], now: 0.2)
        XCTAssertTrue(policy.frames(at: 0.2).isEmpty)
        policy.sync(visible: ["private"], allowed: [], now: 0.3)
        XCTAssertTrue(policy.frames(at: 0.3).isEmpty)
        policy.clear()
        XCTAssertTrue(policy.trackedIDs.isEmpty)
    }
    func testReentryUsesCurrentOpacity() {
        var policy = MapPresencePolicy()
        policy.sync(visible: ["a"], allowed: ["a"], now: 0)
        policy.sync(visible: [], allowed: ["a"], now: 0.2)
        policy.sync(visible: ["a"], allowed: ["a"], now: 0.3)
        XCTAssertEqual(policy.frames(at: 0.3)[0].alpha, 0.5, accuracy: 0.001)
        XCTAssertEqual(policy.frames(at: 0.51)[0].alpha, 1)
    }
    func testPlacementChangesAtZeroWithoutPositionAnimation() {
        var policy = MapPresencePolicy()
        policy.sync(visible: ["a"], allowed: ["a"], now: 0, placements: ["a": 1])
        policy.sync(visible: ["a"], allowed: ["a"], now: 0.2, placements: ["a": 5])
        let frame = policy.frames(at: 0.2)[0]
        XCTAssertEqual(frame.alpha, 0)
        XCTAssertEqual(frame.placement, 5)
        XCTAssertFalse(frame.interactive)
    }
    func testReducedMotionHasNoAnimatedTail() {
        var policy = MapPresencePolicy()
        policy.sync(visible: ["a"], allowed: ["a"], now: 0, animations: false)
        XCTAssertEqual(policy.frames(at: 0)[0].alpha, 1)
        XCTAssertFalse(policy.isAnimating(at: 0))
        policy.sync(visible: [], allowed: ["a"], now: 0.1, animations: false)
        XCTAssertTrue(policy.frames(at: 0.1).isEmpty)
    }
}
