import SwiftUI
import XCTest

@testable import KCC

final class SettingsScreenTests: XCTestCase {
    func testAppThemeOverridesAndAutomaticTransitionsUseLocalTime() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Stockholm"))
        let formatter = ISO8601DateFormatter()
        let beforeDay = try XCTUnwrap(formatter.date(from: "2026-11-09T05:59:59Z"))
        let day = try XCTUnwrap(formatter.date(from: "2026-11-09T06:00:00Z"))
        let night = try XCTUnwrap(formatter.date(from: "2026-11-09T18:00:00Z"))
        let nextDay = try XCTUnwrap(formatter.date(from: "2026-11-10T06:00:00Z"))

        XCTAssertNil(AppThemePreference.system.colorScheme(at: day, calendar: calendar))
        XCTAssertEqual(AppThemePreference.light.colorScheme(at: night, calendar: calendar), .light)
        XCTAssertEqual(AppThemePreference.dark.colorScheme(at: day, calendar: calendar), .dark)
        XCTAssertEqual(AppThemePreference.automatic.colorScheme(at: beforeDay, calendar: calendar), .dark)
        XCTAssertEqual(AppThemePreference.automatic.colorScheme(at: day, calendar: calendar), .light)
        XCTAssertEqual(AppThemePreference.automatic.colorScheme(at: night, calendar: calendar), .dark)
        XCTAssertEqual(AppThemePreference.nextTransition(after: beforeDay, calendar: calendar), day)
        XCTAssertEqual(AppThemePreference.nextTransition(after: day, calendar: calendar), night)
        XCTAssertEqual(AppThemePreference.nextTransition(after: night, calendar: calendar), nextDay)
    }

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
