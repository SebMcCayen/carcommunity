import XCTest
@testable import KCC

final class AppUpdateTests: XCTestCase {
    private let storeURL = URL(string: "itms-apps://itunes.apple.com/app/id123")!

    func testSemanticVersionComparisonPadsMissingComponents() throws {
        XCTAssertLessThan(try XCTUnwrap(VersionNumber("1.9")), try XCTUnwrap(VersionNumber("1.10.0")))
        XCTAssertEqual(VersionNumber("2.0"), VersionNumber("2.0.0"))
        XCTAssertNil(VersionNumber("2.beta"))
    }

    func testSafeStoreURLPrefersNumericNativeLinkAndRejectsUntrustedHosts() {
        XCTAssertEqual(
            AppStoreLookupSource.safeStoreURL(trackViewURL: "https://evil.example/app", trackID: 42),
            URL(string: "itms-apps://itunes.apple.com/app/id42")
        )
        XCTAssertNil(AppStoreLookupSource.safeStoreURL(
            trackViewURL: "https://evil.example/app", trackID: nil
        ))
        XCTAssertEqual(
            AppStoreLookupSource.safeStoreURL(
                trackViewURL: "https://apps.apple.com/se/app/example/id42", trackID: nil
            ),
            URL(string: "https://apps.apple.com/se/app/example/id42")
        )
    }

    func testPolicyDefersSameReleaseForSevenDaysButNewReleasePrompts() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let available = availability("2.0")
        let recent = AppUpdateDismissal(identifier: "2.0", dismissedAt: now.addingTimeInterval(-100))
        XCTAssertFalse(AppUpdatePolicy.shouldPresent(
            available, dismissal: recent, now: now, isDriving: false, announcementIsPresented: false
        ))
        XCTAssertTrue(AppUpdatePolicy.shouldPresent(
            availability("2.1"), dismissal: recent, now: now,
            isDriving: false, announcementIsPresented: false
        ))
        XCTAssertTrue(AppUpdatePolicy.shouldPresent(
            available,
            dismissal: AppUpdateDismissal(
                identifier: "2.0",
                dismissedAt: now.addingTimeInterval(-AppUpdatePolicy.dismissalInterval)
            ),
            now: now, isDriving: false, announcementIsPresented: false
        ))
    }

    func testPolicySuppressesPromptsWhileDrivingOrWhatsNewIsOpen() {
        XCTAssertFalse(AppUpdatePolicy.shouldPresent(
            availability("2.0"), dismissal: nil, now: .now,
            isDriving: true, announcementIsPresented: false
        ))
        XCTAssertFalse(AppUpdatePolicy.shouldPresent(
            availability("2.0"), dismissal: nil, now: .now,
            isDriving: false, announcementIsPresented: true
        ))
    }

    func testRequiredOfferIgnoresDismissalButStillWaitsForDriveToEnd() {
        let required = AppUpdateAvailability(
            identifier: "2.0", version: "2.0", storeURL: storeURL, isRequired: true
        )
        let dismissal = AppUpdateDismissal(identifier: "2.0", dismissedAt: .now)
        XCTAssertTrue(AppUpdatePolicy.shouldPresent(
            required, dismissal: dismissal, now: .now,
            isDriving: false, announcementIsPresented: false
        ))
        XCTAssertFalse(AppUpdatePolicy.shouldPresent(
            required, dismissal: dismissal, now: .now,
            isDriving: true, announcementIsPresented: false
        ))
    }

    private func availability(_ identifier: String) -> AppUpdateAvailability {
        AppUpdateAvailability(
            identifier: identifier, version: identifier, storeURL: storeURL, isRequired: false
        )
    }
}
