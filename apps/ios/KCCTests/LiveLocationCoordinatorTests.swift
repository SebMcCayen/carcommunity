import XCTest

@testable import KCC

/// Unit tests for the live-location orchestration: session start/stop
/// lifecycle, fixes flowing ONLY while sharing AND authorized, the cadence
/// throttle in the publish loop, hide-me-now teardown, and the ported
/// toggle/manage-sheet input derivation. No Firebase — the repository is a
/// scripted fake and positions come from ``StubLocationProvider`` (same
/// conventions as ProfileCoordinatorTests).
final class LiveLocationCoordinatorTests: XCTestCase {

    // MARK: - helpers

    /// Mutable wall clock injected into the coordinator so heartbeat/expiry
    /// decisions are deterministic and advanceable.
    private final class TestClock: @unchecked Sendable {
        private let lock = NSLock()
        private var current = Date(timeIntervalSince1970: 1_700_000_000)

        var now: Date {
            lock.lock()
            defer { lock.unlock() }
            return current
        }

        func advance(by interval: TimeInterval) {
            lock.lock()
            current = current.addingTimeInterval(interval)
            lock.unlock()
        }
    }

    private final class FakeLiveLocationRepository: LiveLocationRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var continuations: [UUID: AsyncStream<LiveSessionInfo?>.Continuation] = [:]
        private(set) var startedDurations: [LiveSessionDuration] = []
        private(set) var startedVehicleIds: [String?] = []
        private(set) var publishedCoordinates: [LiveCoordinate] = []
        private(set) var stopCount = 0
        private(set) var hideCount = 0
        private(set) var observedUids: [String] = []
        var uid: String? = "uid-1"
        /// When set, the next command throws it (then clears).
        var nextError: Error?

        /// Pushes a session to every LIVE subscription (a later RTDB frame).
        func emitSession(_ session: LiveSessionInfo?) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(session)
            }
        }

        // NSLock is unavailable directly in async contexts; every async
        // entry point hops through a synchronous helper so the critical
        // section never suspends (the ProfileCoordinatorTests pattern).

        private func takeErrorSynchronized() -> Error? {
            lock.lock()
            defer { lock.unlock() }
            let error = nextError
            nextError = nil
            return error
        }

        private func recordSynchronized(_ mutate: () -> Void) {
            lock.lock()
            mutate()
            lock.unlock()
        }

        func startSession(duration: LiveSessionDuration, vehicleId: String?) async throws {
            if let error = takeErrorSynchronized() { throw error }
            recordSynchronized {
                startedDurations.append(duration)
                startedVehicleIds.append(vehicleId)
            }
        }

        func updatePosition(_ coordinate: LiveCoordinate) async throws {
            if let error = takeErrorSynchronized() { throw error }
            recordSynchronized { publishedCoordinates.append(coordinate) }
        }

        func stopSession() async throws {
            if let error = takeErrorSynchronized() { throw error }
            recordSynchronized { stopCount += 1 }
        }

        func hideMeNow() async throws {
            if let error = takeErrorSynchronized() { throw error }
            recordSynchronized { hideCount += 1 }
        }

        func ownSessionUpdates(uid: String) -> AsyncStream<LiveSessionInfo?> {
            lock.lock()
            observedUids.append(uid)
            lock.unlock()
            return AsyncStream { continuation in
                let id = UUID()
                self.lock.lock()
                self.continuations[id] = continuation
                self.lock.unlock()
                continuation.onTermination = { [weak self] _ in
                    guard let self else { return }
                    self.lock.lock()
                    self.continuations[id] = nil
                    self.lock.unlock()
                }
            }
        }

        func currentUserId() -> String? { uid }
    }

    private struct FakeError: Error {}

    private let clock = TestClock()

    @MainActor
    private func makeCoordinator(
        repository: FakeLiveLocationRepository?,
        provider: StubLocationProvider,
        canShare: Bool = true
    ) -> LiveLocationCoordinator {
        let clock = clock
        return LiveLocationCoordinator(
            repository: repository,
            provider: provider,
            canShare: canShare,
            now: { clock.now },
            // Fast expiry-watchdog tick so watchdog behavior is testable
            // without waiting out the production 15 s interval.
            expiryTickWait: { try await Task.sleep(nanoseconds: 5_000_000) }
        )
    }

    private func activeSession(expiresIn interval: TimeInterval = 6 * 3600) -> LiveSessionInfo {
        LiveSessionInfo(
            sessionId: "session-1",
            status: .active,
            duration: .sixHours,
            expiresAt: clock.now.addingTimeInterval(interval)
        )
    }

    private func fix(latitude: Double, longitude: Double) -> LocationFix {
        LocationFix.of(latitude: latitude, longitude: longitude, timestamp: clock.now)!
    }

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

    // MARK: - start/stop lifecycle

    @MainActor
    func testStartSharingCallsTheCallableWithTheFixedDefaultWindow() async {
        let repository = FakeLiveLocationRepository()
        let coordinator = makeCoordinator(repository: repository, provider: StubLocationProvider())
        coordinator.start()

        let result = await coordinator.startSharing()

        XCTAssertEqual(result, .success)
        XCTAssertEqual(repository.startedDurations, [.sixHours])
        XCTAssertEqual(repository.startedVehicleIds, [nil])
        XCTAssertEqual(coordinator.actionStatus, .idle)
    }

    @MainActor
    func testStartIsIdempotentAndObservesTheSignedInUid() async {
        let repository = FakeLiveLocationRepository()
        let coordinator = makeCoordinator(repository: repository, provider: StubLocationProvider())
        coordinator.start()
        coordinator.start()

        await waitUntil { repository.observedUids == ["uid-1"] }
    }

    @MainActor
    func testSessionEmissionDrivesIsSharing() async {
        let repository = FakeLiveLocationRepository()
        let coordinator = makeCoordinator(repository: repository, provider: StubLocationProvider())
        coordinator.start()
        XCTAssertFalse(coordinator.isSharing)

        repository.emitSession(activeSession())
        await waitUntil { coordinator.isSharing }

        repository.emitSession(nil)
        await waitUntil { !coordinator.isSharing }
    }

    @MainActor
    func testStopSharingCallsTheCallableAndTearsDownTheFixStream() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { provider.activeFixStreamCount == 1 }

        let result = await coordinator.stopSharing()

        XCTAssertEqual(result, .success)
        XCTAssertEqual(repository.stopCount, 1)
        // The stream is torn down BEFORE the callable resolves server-side —
        // the GPS must not wait for the session echo.
        await waitUntil { provider.activeFixStreamCount == 0 }
    }

    // MARK: - fixes flow only while sharing AND authorized

    @MainActor
    func testNoFixStreamExistsWhileNotSharing() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()

        provider.emitFix(fix(latitude: 57.5, longitude: 12.1))
        await Task.yield()

        XCTAssertEqual(provider.activeFixStreamCount, 0)
        XCTAssertTrue(repository.publishedCoordinates.isEmpty)
    }

    @MainActor
    func testAuthorizedFixPublishesWhileSharing() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { provider.activeFixStreamCount == 1 }

        provider.emitFix(fix(latitude: 57.5, longitude: 12.1))

        await waitUntil { repository.publishedCoordinates.count == 1 }
        XCTAssertEqual(repository.publishedCoordinates.first?.latitude, 57.5)
    }

    @MainActor
    func testUnauthorizedFixesNeverReachTheRepository() async {
        let repository = FakeLiveLocationRepository()
        // Sharing is active but the app is NOT authorized: the provider
        // contract makes the stream yield nothing, so nothing publishes.
        let provider = StubLocationProvider(authorization: .denied)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { provider.activeFixStreamCount == 1 }

        provider.emitFix(fix(latitude: 57.5, longitude: 12.1))
        await Task.yield()
        await Task.yield()

        XCTAssertTrue(repository.publishedCoordinates.isEmpty)
    }

    @MainActor
    func testSessionGoingAwayTearsDownTheFixStream() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { provider.activeFixStreamCount == 1 }

        repository.emitSession(
            LiveSessionInfo(sessionId: "session-1", status: .stopped, duration: .sixHours, expiresAt: nil)
        )

        await waitUntil { provider.activeFixStreamCount == 0 }
    }

    @MainActor
    func testExpiredSessionStopsPublishingOnTheNextFix() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession(expiresIn: 60))
        await waitUntil { provider.activeFixStreamCount == 1 }

        // The session runs out with no RTDB frame telling us so; the next
        // fix must end the loop (and the GPS) instead of publishing.
        clock.advance(by: 120)
        provider.emitFix(fix(latitude: 57.5, longitude: 12.1))

        await waitUntil { provider.activeFixStreamCount == 0 }
        XCTAssertTrue(repository.publishedCoordinates.isEmpty)
    }

    @MainActor
    func testExpiryWatchdogTearsDownWithoutAnyFixes() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession(expiresIn: 60))
        await waitUntil { provider.activeFixStreamCount == 1 }

        // The session expires and CoreLocation delivers NOTHING (deep
        // indoors): the expiry ticker alone must release the stream — the
        // per-fix guard never runs (Android's EXPIRY_TICK_MS parity).
        clock.advance(by: 120)

        await waitUntil { provider.activeFixStreamCount == 0 }
        XCTAssertTrue(repository.publishedCoordinates.isEmpty)
    }

    // MARK: - cadence gating

    @MainActor
    func testJitterWithinHeartbeatIsThrottledAndMovementPublishes() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { provider.activeFixStreamCount == 1 }

        // First fix of the session always publishes.
        provider.emitFix(fix(latitude: 57.5, longitude: 12.1))
        await waitUntil { repository.publishedCoordinates.count == 1 }

        // ~5 m of GPS jitter a few seconds later: throttled.
        clock.advance(by: 5)
        provider.emitFix(fix(latitude: 57.500045, longitude: 12.1))
        await Task.yield()
        await Task.yield()
        XCTAssertEqual(repository.publishedCoordinates.count, 1)

        // ~20 m of movement: publishes.
        clock.advance(by: 5)
        provider.emitFix(fix(latitude: 57.50018, longitude: 12.1))
        await waitUntil { repository.publishedCoordinates.count == 2 }
    }

    @MainActor
    func testStationaryHeartbeatPublishesWithoutMovement() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { provider.activeFixStreamCount == 1 }

        provider.emitFix(fix(latitude: 57.5, longitude: 12.1))
        await waitUntil { repository.publishedCoordinates.count == 1 }

        // Parked: same position past the 3 min heartbeat publishes again.
        clock.advance(by: LiveShareCadence.stationaryHeartbeat)
        provider.emitFix(fix(latitude: 57.5, longitude: 12.1))
        await waitUntil { repository.publishedCoordinates.count == 2 }
    }

    // MARK: - hide me now

    @MainActor
    func testHideMeNowCallsTheCallableAndTearsDownImmediately() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { provider.activeFixStreamCount == 1 }

        let result = await coordinator.hideMeNow()

        XCTAssertEqual(result, .success)
        XCTAssertEqual(repository.hideCount, 1)
        await waitUntil { provider.activeFixStreamCount == 0 }
    }

    @MainActor
    func testFailedStopResumesPublishingWhileStillSharing() async {
        let repository = FakeLiveLocationRepository()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository: repository, provider: provider)
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { provider.activeFixStreamCount == 1 }

        repository.nextError = FakeError()
        let result = await coordinator.stopSharing()

        XCTAssertEqual(result, .failed)
        XCTAssertEqual(coordinator.actionStatus, .failed)
        // The session is still active server-side: publishing must resume so
        // the marker does not silently go stale.
        await waitUntil { provider.activeFixStreamCount == 1 }

        coordinator.reset()
        XCTAssertEqual(coordinator.actionStatus, .idle)
    }

    // MARK: - command overlap / failure

    @MainActor
    func testFailedStartSurfacesFailedStatus() async {
        let repository = FakeLiveLocationRepository()
        let coordinator = makeCoordinator(repository: repository, provider: StubLocationProvider())
        coordinator.start()
        repository.nextError = FakeError()

        let result = await coordinator.startSharing()

        XCTAssertEqual(result, .failed)
        XCTAssertEqual(coordinator.actionStatus, .failed)
    }

    @MainActor
    func testUnwiredCommandsFailWithoutCrashing() async {
        let coordinator = makeCoordinator(repository: nil, provider: StubLocationProvider())
        coordinator.start()

        let result = await coordinator.startSharing()

        XCTAssertEqual(result, .failed)
        XCTAssertFalse(coordinator.wired)
    }

    // MARK: - ported toggle/manage-sheet input derivation

    @MainActor
    func testToggleActionDerivesFromObservedState() async {
        let repository = FakeLiveLocationRepository()
        let coordinator = makeCoordinator(repository: repository, provider: StubLocationProvider())
        coordinator.start()

        // Wired, idle, flag on → start.
        XCTAssertEqual(coordinator.toggleAction, .start)

        // Flag off → the screen (informational) instead of a dead start.
        coordinator.canShare = false
        XCTAssertEqual(coordinator.toggleAction, .openScreen)

        // Sharing → stop, regardless of the flag.
        repository.emitSession(activeSession())
        await waitUntil { coordinator.isSharing }
        XCTAssertEqual(coordinator.toggleAction, .stop)
    }

    @MainActor
    func testUnwiredToggleAlwaysOpensTheScreen() {
        let coordinator = makeCoordinator(repository: nil, provider: StubLocationProvider())
        XCTAssertEqual(coordinator.toggleAction, .openScreen)
    }

    @MainActor
    func testNoSignedInUidCountsAsUnwired() {
        let repository = FakeLiveLocationRepository()
        repository.uid = nil
        let coordinator = makeCoordinator(repository: repository, provider: StubLocationProvider())
        XCTAssertFalse(coordinator.wired)
        XCTAssertEqual(coordinator.toggleAction, .openScreen)
    }

    @MainActor
    func testManageRowsPassThroughThePortedSheetRules() async {
        let repository = FakeLiveLocationRepository()
        let coordinator = makeCoordinator(repository: repository, provider: StubLocationProvider())
        coordinator.start()
        repository.emitSession(activeSession())
        await waitUntil { coordinator.isSharing }

        // The rules themselves are LiveManageSheet's (tested in
        // ShellNavTests); here we assert the coordinator feeds it the
        // observed state faithfully on both sheet variants.
        XCTAssertEqual(
            coordinator.manageRows(hasStop: true),
            LiveManageSheet.actions(isSharing: true, canShareLive: true, hasStop: true)
        )
        XCTAssertEqual(
            coordinator.manageRows(hasStop: false),
            LiveManageSheet.actions(isSharing: true, canShareLive: true, hasStop: false)
        )
    }
}
