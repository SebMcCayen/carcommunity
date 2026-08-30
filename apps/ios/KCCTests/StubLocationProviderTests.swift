import XCTest

@testable import KCC

/// Unit tests for the scripted stub provider itself — the contract every
/// consumer test (coordinator today; puck, drives, live sharing later) leans
/// on: streams yield the current value first, the request trigger honors the
/// only-from-not-determined rule, and fix-stream teardown is observable.
final class StubLocationProviderTests: XCTestCase {

    @MainActor
    private func waitUntil(
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for condition", file: file, line: line)
    }

    // MARK: - authorization stream

    @MainActor
    func testAuthorizationStreamYieldsCurrentValueFirstThenChanges() async {
        let provider = StubLocationProvider(authorization: .notDetermined)
        let stream = provider.authorizationUpdates()

        var iterator = stream.makeAsyncIterator()
        let first = await iterator.next()
        XCTAssertEqual(first, .notDetermined)

        provider.setAuthorization(.whileInUse)
        let second = await iterator.next()
        XCTAssertEqual(second, .whileInUse)
    }

    // MARK: - request trigger

    @MainActor
    func testRequestFromNotDeterminedCountsAndAppliesScriptedOutcome() {
        let provider = StubLocationProvider(authorization: .notDetermined)
        provider.scriptRequestOutcome(.whileInUse)

        provider.requestWhenInUseAuthorization()

        XCTAssertEqual(provider.whenInUseRequestCount, 1)
        XCTAssertEqual(provider.authorization, .whileInUse)
    }

    @MainActor
    func testRequestWithoutScriptedOutcomeLeavesTheDialogPending() {
        let provider = StubLocationProvider(authorization: .notDetermined)

        provider.requestWhenInUseAuthorization()

        XCTAssertEqual(provider.whenInUseRequestCount, 1)
        XCTAssertEqual(provider.authorization, .notDetermined)
    }

    @MainActor
    func testRequestOutsideNotDeterminedIsANoOp() {
        // iOS never re-raises the dialog after an answer; the stub must not
        // pretend otherwise, or coordinator tests would pass against
        // behaviour the real provider cannot deliver.
        let provider = StubLocationProvider(authorization: .denied)
        provider.scriptRequestOutcome(.whileInUse)

        provider.requestWhenInUseAuthorization()

        XCTAssertEqual(provider.whenInUseRequestCount, 0)
        XCTAssertEqual(provider.authorization, .denied)
    }

    // MARK: - fix streams

    @MainActor
    func testEmittedFixesReachALiveStream() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let stream = provider.fixes()
        XCTAssertEqual(provider.activeFixStreamCount, 1)

        let fix = LocationFix.of(
            latitude: 57.487,
            longitude: 12.076,
            timestamp: Date(timeIntervalSince1970: 1_700_000_000),
            speedMetersPerSecond: 13.9
        )!

        var iterator = stream.makeAsyncIterator()
        provider.emitFix(fix)
        let received = await iterator.next()
        XCTAssertEqual(received, fix)
    }

    @MainActor
    func testTerminatingAFixStreamReleasesTheDemandCount() async {
        let provider = StubLocationProvider(authorization: .whileInUse)

        var task: Task<Void, Never>?
        autoreleasepool {
            let stream = provider.fixes()
            task = Task {
                for await _ in stream {}
            }
        }
        XCTAssertEqual(provider.activeFixStreamCount, 1)

        // The consumer goes away — the demand count must drop, which is
        // what stops the hardware in the real provider.
        task?.cancel()
        await waitUntil { provider.activeFixStreamCount == 0 }
    }

    @MainActor
    func testEmittedFixesAreDroppedWhileUnauthorized() async {
        // The protocol promises an unauthorized stream yields nothing; the
        // stub must enforce it, or consumer tests could pass under behavior
        // the real provider can never deliver.
        let provider = StubLocationProvider(authorization: .denied)
        let stream = provider.fixes()

        let dropped = LocationFix.of(
            latitude: 1, longitude: 2, timestamp: Date(timeIntervalSince1970: 0)
        )!
        provider.emitFix(dropped)

        // A grant later lets fixes through on the SAME stream.
        provider.setAuthorization(.whileInUse)
        let delivered = LocationFix.of(
            latitude: 3, longitude: 4, timestamp: Date(timeIntervalSince1970: 1)
        )!
        var iterator = stream.makeAsyncIterator()
        provider.emitFix(delivered)

        let first = await iterator.next()
        XCTAssertEqual(first, delivered)
    }

    @MainActor
    func testTwoStreamsBothReceiveAndCountIndependently() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let first = provider.fixes()
        let second = provider.fixes()
        XCTAssertEqual(provider.activeFixStreamCount, 2)

        let fix = LocationFix.of(
            latitude: 1, longitude: 2, timestamp: Date(timeIntervalSince1970: 0)
        )!
        var firstIterator = first.makeAsyncIterator()
        var secondIterator = second.makeAsyncIterator()
        provider.emitFix(fix)

        let a = await firstIterator.next()
        let b = await secondIterator.next()
        XCTAssertEqual(a, fix)
        XCTAssertEqual(b, fix)
    }
}
