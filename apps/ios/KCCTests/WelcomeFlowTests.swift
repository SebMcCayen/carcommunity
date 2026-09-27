import XCTest
@testable import KCC

final class WelcomeFlowTests: XCTestCase {
    func testFourStepFlowAdvancesAndStopsAtProfile() {
        XCTAssertEqual(WelcomeStep.allCases.count, 4)
        XCTAssertEqual(WelcomeStep.welcome.position, 1)
        XCTAssertEqual(WelcomeStep.welcome.next, .map)
        XCTAssertEqual(WelcomeStep.map.next, .membership)
        XCTAssertEqual(WelcomeStep.membership.next, .profile)
        XCTAssertTrue(WelcomeStep.profile.isLast)
        XCTAssertEqual(WelcomeStep.profile.next, .profile)
    }

    func testStoreIsScopedPerUid() {
        let suite = "WelcomeFlowTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = WelcomeStore(defaults: defaults)
        XCTAssertFalse(store.hasSeenWelcome(uid: "a"))
        store.markSeen(uid: "a")
        XCTAssertTrue(store.hasSeenWelcome(uid: "a"))
        XCTAssertFalse(store.hasSeenWelcome(uid: "b"))
    }
}
