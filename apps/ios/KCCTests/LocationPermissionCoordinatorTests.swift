import XCTest

@testable import KCC

/// Unit tests for the location ask-flow state machine, driven end to end
/// through ``StubLocationProvider`` — no CoreLocation, no device. Covers the
/// parity rules the coordinator exists to enforce: the rationale ALWAYS
/// precedes the system dialog, nothing prompts until a feature is used, and
/// a denial resolves to the settings-hint (iOS never re-shows the dialog).
final class LocationPermissionCoordinatorTests: XCTestCase {

    /// Polls until `predicate` holds, yielding so the coordinator's
    /// authorization subscription drains. Fails the test on timeout.
    @MainActor
    private func waitForState(
        of coordinator: LocationPermissionCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (LocationPermissionFlowState) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator.state) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail(
            "Timed out waiting for state; last: \(coordinator.state)",
            file: file,
            line: line
        )
    }

    /// Lets in-flight stream hops settle, then asserts the state HELD —
    /// for the transitions that must NOT happen.
    @MainActor
    private func assertStateStays(
        _ expected: LocationPermissionFlowState,
        of coordinator: LocationPermissionCoordinator,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        for _ in 0..<20 {
            await Task.yield()
        }
        XCTAssertEqual(coordinator.state, expected, file: file, line: line)
    }

    // MARK: - starting is not asking

    @MainActor
    func testStartNeverPrompts() async {
        let provider = StubLocationProvider(authorization: .notDetermined)
        let coordinator = LocationPermissionCoordinator(provider: provider)

        coordinator.start()
        await assertStateStays(.idle, of: coordinator)
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testStartFoldsAnExistingGrantToGranted() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = LocationPermissionCoordinator(provider: provider)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .granted }
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testStartStaysQuietForAPreviouslyDeniedApp() async {
        // A denial that lands while nothing is asking must not nag — the
        // settings-hint appears only when the feature is used again.
        let provider = StubLocationProvider(authorization: .denied)
        let coordinator = LocationPermissionCoordinator(provider: provider)

        coordinator.start()
        await assertStateStays(.idle, of: coordinator)
    }

    @MainActor
    func testStartIsIdempotent() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = LocationPermissionCoordinator(provider: provider)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .granted }
        coordinator.start()
        await assertStateStays(.granted, of: coordinator)
    }

    // MARK: - the happy ask-flow: rationale before dialog

    @MainActor
    func testRequestAccessFromNotDeterminedShowsRationaleWithoutPrompting() async {
        let provider = StubLocationProvider(authorization: .notDetermined)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()

        coordinator.requestAccess()

        // The WHY comes first — the system dialog has not been spent.
        XCTAssertEqual(coordinator.state, .rationale)
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testProceedFromRationaleRaisesTheDialogExactlyOnce() async {
        let provider = StubLocationProvider(authorization: .notDetermined)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        coordinator.requestAccess()

        coordinator.proceedFromRationale()

        XCTAssertEqual(coordinator.state, .requesting)
        XCTAssertEqual(provider.whenInUseRequestCount, 1)
    }

    @MainActor
    func testGrantWhileRequestingBecomesGranted() async {
        let provider = StubLocationProvider(authorization: .notDetermined)
        provider.scriptRequestOutcome(.whileInUse)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        coordinator.requestAccess()

        coordinator.proceedFromRationale()

        await waitForState(of: coordinator) { $0 == .granted }
    }

    @MainActor
    func testDenialWhileRequestingBecomesSettingsHint() async {
        let provider = StubLocationProvider(authorization: .notDetermined)
        provider.scriptRequestOutcome(.denied)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        coordinator.requestAccess()

        coordinator.proceedFromRationale()

        // iOS never re-shows the dialog: a denial's only remedy is Settings.
        await waitForState(of: coordinator) { $0 == .deniedNeedsSettings }
    }

    @MainActor
    func testDismissRationaleBacksOutWithoutSpendingTheDialog() async {
        let provider = StubLocationProvider(authorization: .notDetermined)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        coordinator.requestAccess()

        coordinator.dismissRationale()

        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(provider.whenInUseRequestCount, 0)

        // "Not now" is not "never": the feature can ask again later.
        coordinator.requestAccess()
        XCTAssertEqual(coordinator.state, .rationale)
    }

    // MARK: - asking in the other authorization states

    @MainActor
    func testRequestAccessWhenAlreadyGrantedIsGranted() async {
        let provider = StubLocationProvider(authorization: .always)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()

        coordinator.requestAccess()

        XCTAssertEqual(coordinator.state, .granted)
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testRequestAccessWhenDeniedShowsSettingsHintWithoutPrompting() async {
        let provider = StubLocationProvider(authorization: .denied)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()

        coordinator.requestAccess()

        XCTAssertEqual(coordinator.state, .deniedNeedsSettings)
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testDismissSettingsHintReturnsToIdleButTheDenialStands() async {
        let provider = StubLocationProvider(authorization: .denied)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        coordinator.requestAccess()

        coordinator.dismissSettingsHint()
        XCTAssertEqual(coordinator.state, .idle)

        // Using the feature again re-surfaces the hint (still denied).
        coordinator.requestAccess()
        XCTAssertEqual(coordinator.state, .deniedNeedsSettings)
    }

    // MARK: - outside changes (the Settings app, restrictions)

    @MainActor
    func testGrantFromSettingsWhileHintShowingBecomesGranted() async {
        let provider = StubLocationProvider(authorization: .denied)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        coordinator.requestAccess()
        XCTAssertEqual(coordinator.state, .deniedNeedsSettings)

        // The user followed the hint and flipped the switch in Settings.
        provider.setAuthorization(.whileInUse)

        await waitForState(of: coordinator) { $0 == .granted }
    }

    @MainActor
    func testRevocationWhileGrantedSurfacesTheSettingsHint() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .granted }

        // A running feature must learn WHY its position vanished.
        provider.setAuthorization(.denied)

        await waitForState(of: coordinator) { $0 == .deniedNeedsSettings }
    }

    @MainActor
    func testPermissionResetWhileGrantedReturnsQuietlyToIdle() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .granted }

        // Settings-app reset: back to never-asked, with no prompt and no
        // nag until the feature is used again.
        provider.setAuthorization(.notDetermined)

        await waitForState(of: coordinator) { $0 == .idle }
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testNotDeterminedWhileRequestingKeepsWaiting() async {
        // While our dialog is up the status legitimately still reads
        // not-determined — that must not bounce the flow out of requesting.
        let provider = StubLocationProvider(authorization: .notDetermined)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        coordinator.requestAccess()
        coordinator.proceedFromRationale()

        provider.setAuthorization(.notDetermined)

        await assertStateStays(.requesting, of: coordinator)
    }

    // MARK: - guard rails

    @MainActor
    func testProceedFromRationaleOutsideRationaleIsANoOp() async {
        let provider = StubLocationProvider(authorization: .notDetermined)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()

        // Never entered the rationale — must not raise the dialog.
        coordinator.proceedFromRationale()

        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(provider.whenInUseRequestCount, 0)
    }

    @MainActor
    func testDismissalsOutsideTheirStatesAreNoOps() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = LocationPermissionCoordinator(provider: provider)
        coordinator.start()
        await waitForState(of: coordinator) { $0 == .granted }

        coordinator.dismissRationale()
        coordinator.dismissSettingsHint()

        XCTAssertEqual(coordinator.state, .granted)
    }
}
