import XCTest

@testable import KCC

final class SettingsScreenTests: XCTestCase {
    func testMissingCallbacksOmitEveryInAppDestination() {
        XCTAssertTrue(SettingsActions().availableDestinations.isEmpty)
    }

    func testAvailabilityIncludesExactlyTheSuppliedCallbacks() {
        let actions = SettingsActions(
            onNotificationSettings: {},
            onBlockedUsers: {},
            onFeedback: {}
        )

        XCTAssertEqual(
            actions.availableDestinations,
            [.notificationSettings, .blockedUsers, .feedback]
        )
    }

    func testAllShellDestinationsCanBeEnabledIndependently() {
        let actions = SettingsActions(
            onManageSubscription: {},
            onSavedPlaces: {},
            onNotificationSettings: {},
            onBlockedUsers: {},
            onPartnerStats: {},
            onFeedback: {},
            onDeleteAccount: {},
            onWhatsNew: {}
        )

        XCTAssertEqual(actions.availableDestinations, [
            .subscription, .savedPlaces, .notificationSettings, .blockedUsers,
            .partnerStats, .feedback, .accountDeletion, .whatsNew,
        ])
    }
}
