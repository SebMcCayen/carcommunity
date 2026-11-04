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
                "https://kungsbackacarcommunity.se/integritetspolicy.html",
                expectedPath: "/integritetspolicy.html"
            )?.absoluteString,
            "https://kungsbackacarcommunity.se/integritetspolicy.html"
        )
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "http://kungsbackacarcommunity.se/integritetspolicy.html",
            expectedPath: "/integritetspolicy.html"
        ))
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "https://kungsbackacarcommunity.se.evil.example/integritetspolicy.html",
            expectedPath: "/integritetspolicy.html"
        ))
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "https://kungsbackacarcommunity.se/anvandarvillkor.html",
            expectedPath: "/integritetspolicy.html"
        ))
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "https://user@kungsbackacarcommunity.se/integritetspolicy.html",
            expectedPath: "/integritetspolicy.html"
        ))
        for suffix in [":443/integritetspolicy.html", "/integritetspolicy.html?x=1", "/integritetspolicy.html#x"] {
            XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
                "https://kungsbackacarcommunity.se\(suffix)",
                expectedPath: "/integritetspolicy.html"
            ))
        }
        XCTAssertNil(SettingsLegalLinkPolicy.validatedURL(
            "https://kungsbacka-car-community.web.app/privacy",
            expectedPath: "/integritetspolicy.html"
        ))
    }

    func testBundledLegalLinksAreAcceptedByTheSettingsPolicy() {
        let links: [(String.LocalizationValue, String)] = [
            ("url.privacy", "/integritetspolicy.html"),
            ("url.terms", "/anvandarvillkor.html"),
        ]
        for (key, path) in links {
            XCTAssertNotNil(SettingsLegalLinkPolicy.validatedURL(
                String(localized: key),
                expectedPath: path
            ))
        }
    }
}
