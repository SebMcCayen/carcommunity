import XCTest
@testable import KCC

final class MapStartupAndPanelTests: XCTestCase {
    func testInitialFollowWaitsForConsentActiveMapAndRendererThenArmsOnlyOnce() {
        var startup = MapHomeStartupCameraPolicy()
        XCTAssertFalse(startup.takeFollow(authorized: false, active: true, rendererReady: true))
        XCTAssertFalse(startup.takeFollow(authorized: true, active: false, rendererReady: true))
        XCTAssertFalse(startup.takeFollow(authorized: true, active: true, rendererReady: false))
        XCTAssertTrue(startup.pending)
        XCTAssertFalse(startup.following)
        XCTAssertTrue(startup.takeFollow(authorized: true, active: true, rendererReady: true))
        XCTAssertTrue(startup.following, "Style reloads must preserve the native location-follow owner")
        XCTAssertFalse(startup.takeFollow(authorized: true, active: true, rendererReady: true))
        startup.cancel()
        XCTAssertFalse(startup.following, "Manual intent/revocation releases the camera owner")
    }

    func testManualCameraIntentCancelsDelayedStartupFollowing() {
        var startup = MapHomeStartupCameraPolicy()
        startup.cancel()
        XCTAssertFalse(startup.takeFollow(authorized: true, active: true, rendererReady: true))
    }

    func testPanelReleaseDistinguishesShortDragFlingAndChangedMind() {
        XCTAssertFalse(ShellPanelDragPolicy.shouldDismiss(offset: 0, predictedOffset: 300, panelHeight: 800))
        XCTAssertFalse(ShellPanelDragPolicy.shouldDismiss(offset: 300, predictedOffset: 300, panelHeight: 0))
        XCTAssertFalse(ShellPanelDragPolicy.shouldDismiss(offset: 40, predictedOffset: 40, panelHeight: 800))
        XCTAssertTrue(ShellPanelDragPolicy.shouldDismiss(offset: 300, predictedOffset: 300, panelHeight: 800))
        XCTAssertTrue(ShellPanelDragPolicy.shouldDismiss(offset: 40, predictedOffset: 200, panelHeight: 800))
        XCTAssertFalse(ShellPanelDragPolicy.shouldDismiss(offset: 300, predictedOffset: 100, panelHeight: 800))
        XCTAssertFalse(ShellPanelDragPolicy.shouldDismiss(offset: -30, predictedOffset: -30, panelHeight: 800))
    }
}
