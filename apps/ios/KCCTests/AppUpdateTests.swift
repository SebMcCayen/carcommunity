import XCTest
@testable import KCC

final class AppUpdateTests: XCTestCase {
    private let storeURL = URL(string: "itms-apps://itunes.apple.com/app/id123")!

    func testSemanticVersionComparisonPadsMissingComponents() throws {
        XCTAssertLessThan(try XCTUnwrap(VersionNumber("1.9")), try XCTUnwrap(VersionNumber("1.10.0")))
        XCTAssertEqual(VersionNumber("2.0"), VersionNumber("2.0.0"))
        XCTAssertNil(VersionNumber("2.beta"))
        XCTAssertNil(VersionNumber("1.\(String(repeating: "9", count: 100))"))
        XCTAssertNil(VersionNumber("١.٢"))
    }

    func testSafeStoreURLPrefersNumericNativeLinkAndRejectsUntrustedHosts() {
        XCTAssertEqual(
            AppStoreLookupSource.safeStoreURL(trackViewURL: "https://evil.example/app", trackID: 42),
            URL(string: "itms-apps://itunes.apple.com/app/id42")
        )
        XCTAssertNil(AppStoreLookupSource.safeStoreURL(
            trackViewURL: "https://user@apps.apple.com/se/app/example/id42", trackID: nil
        ))
        XCTAssertNil(AppStoreLookupSource.safeStoreURL(
            trackViewURL: "https://apps.apple.com:444/se/app/example/id42", trackID: nil
        ))
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

    func testLookupResponseIsBoundedAndMatchesBundle() throws {
        let valid = Data("""
        {"results":[{"bundleId":"se.kcc.app","version":"2.0.0","trackViewUrl":null,"trackId":42}]}
        """.utf8)
        XCTAssertEqual(
            AppStoreLookupSource.availability(
                from: valid, bundleIdentifier: "se.kcc.app", currentVersion: "1.9"
            )?.version,
            "2.0.0"
        )
        XCTAssertNil(AppStoreLookupSource.availability(
            from: valid, bundleIdentifier: "se.other.app", currentVersion: "1.9"
        ))

        let oversized = Data(repeating: 0x20, count: AppStoreLookupSource.maximumResponseBytes + 1)
        XCTAssertNil(AppStoreLookupSource.availability(
            from: oversized, bundleIdentifier: "se.kcc.app", currentVersion: "1.9"
        ))

        let result = "{\"bundleId\":\"se.other.app\",\"version\":\"2.0\",\"trackViewUrl\":null,\"trackId\":42}"
        let tooManyResults = Data(
            "{\"results\":[\(Array(repeating: result, count: AppStoreLookupSource.maximumResultCount + 1).joined(separator: ","))]}".utf8
        )
        XCTAssertNil(AppStoreLookupSource.availability(
            from: tooManyResults, bundleIdentifier: "se.kcc.app", currentVersion: "1.9"
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

    @MainActor
    func testAcceptedHandoffClearsOnlyOptionalOffer() async {
        let optional = availability("2.0")
        let required = AppUpdateAvailability(
            identifier: "3.0", version: "3.0", storeURL: storeURL, isRequired: true
        )

        let optionalCoordinator = AppUpdateCoordinator(source: StubAppUpdateSource([optional]))
        await optionalCoordinator.checkOnce()
        optionalCoordinator.accepted()
        XCTAssertNil(optionalCoordinator.availability)

        let requiredCoordinator = AppUpdateCoordinator(source: StubAppUpdateSource([required]))
        await requiredCoordinator.checkOnce()
        requiredCoordinator.accepted()
        XCTAssertEqual(requiredCoordinator.availability, required)
    }

    @MainActor
    func testRequiredOfferIsRecheckedWhenAppReturns() async {
        let required = AppUpdateAvailability(
            identifier: "3.0", version: "3.0", storeURL: storeURL, isRequired: true
        )
        let source = StubAppUpdateSource([required, nil])
        let coordinator = AppUpdateCoordinator(source: source)

        await coordinator.checkOnce()
        XCTAssertEqual(coordinator.availability, required)
        await coordinator.recheckRequiredUpdate()
        XCTAssertNil(coordinator.availability)
    }

    @MainActor
    func testNewestRequiredOfferRecheckWinsWhenResponsesCompleteOutOfOrder() async {
        let original = requiredAvailability("3.0")
        let stale = requiredAvailability("3.1")
        let newest = requiredAvailability("4.0")
        let source = ControlledAppUpdateSource()
        let coordinator = AppUpdateCoordinator(source: source)

        let initialCheck = Task { await coordinator.checkOnce() }
        await source.waitForRequestCount(1)
        await source.resolveRequest(at: 0, with: original)
        await initialCheck.value

        let olderRecheck = Task { await coordinator.recheckRequiredUpdate() }
        await source.waitForRequestCount(2)
        let newerRecheck = Task { await coordinator.recheckRequiredUpdate() }
        await source.waitForRequestCount(3)

        await source.resolveRequest(at: 2, with: newest)
        await newerRecheck.value
        XCTAssertEqual(coordinator.availability, newest)

        await source.resolveRequest(at: 1, with: stale)
        await olderRecheck.value
        XCTAssertEqual(coordinator.availability, newest)
    }

    private func availability(_ identifier: String) -> AppUpdateAvailability {
        AppUpdateAvailability(
            identifier: identifier, version: identifier, storeURL: storeURL, isRequired: false
        )
    }

    private func requiredAvailability(_ identifier: String) -> AppUpdateAvailability {
        AppUpdateAvailability(
            identifier: identifier,
            version: identifier,
            storeURL: storeURL,
            isRequired: true
        )
    }
}

private actor StubAppUpdateSource: AppUpdateSource {
    private var responses: [AppUpdateAvailability?]

    init(_ responses: [AppUpdateAvailability?]) {
        self.responses = responses
    }

    func fetch() async -> AppUpdateAvailability? {
        guard !responses.isEmpty else { return nil }
        return responses.removeFirst()
    }
}

private actor ControlledAppUpdateSource: AppUpdateSource {
    private var requests: [CheckedContinuation<AppUpdateAvailability?, Never>?] = []
    private var countWaiters: [(Int, CheckedContinuation<Void, Never>)] = []

    func fetch() async -> AppUpdateAvailability? {
        await withCheckedContinuation { continuation in
            requests.append(continuation)
            let ready = countWaiters.filter { requests.count >= $0.0 }
            countWaiters.removeAll { requests.count >= $0.0 }
            ready.forEach { $0.1.resume() }
        }
    }

    func waitForRequestCount(_ count: Int) async {
        guard requests.count < count else { return }
        await withCheckedContinuation { countWaiters.append((count, $0)) }
    }

    func resolveRequest(at index: Int, with value: AppUpdateAvailability?) {
        requests[index]?.resume(returning: value)
        requests[index] = nil
    }
}
