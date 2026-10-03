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
        for destination in SettingsDestination.allCases {
            XCTAssertNotNil(actions.action(for: destination))
        }
    }

    func testLegalLinksRequireCanonicalHTTPSOriginAndExactPath() {
        XCTAssertEqual(
            SettingsLegalLinkPolicy.validatedURL(
                "https://kungsbacka-car-community.web.app/privacy",
                expectedPath: "/privacy"
            )?.absoluteString,
            "https://kungsbacka-car-community.web.app/privacy"
        )
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "http://kungsbacka-car-community.web.app/privacy",
            expectedPath: "/privacy"
        ))
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "https://kungsbacka-car-community.web.app.evil.example/privacy",
            expectedPath: "/privacy"
        ))
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "https://kungsbacka-car-community.web.app/terms",
            expectedPath: "/privacy"
        ))
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "https://user@kungsbacka-car-community.web.app/privacy",
            expectedPath: "/privacy"
        ))
    }
}
