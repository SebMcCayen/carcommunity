import XCTest

@testable import KCC

@MainActor
final class DriveRecordingCoordinatorTests: XCTestCase {
    private final class FakeRepository: DriveRecordingRepository, @unchecked Sendable {
        var requests: [DriveSaveRequest] = []
        var uploads: [(String, [RecordedDrivePoint])] = []
        var failures: [Error] = []
        var delayNextSave = false
        private var delayedSaveContinuation: CheckedContinuation<Void, Never>?

        func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult {
            requests.append(request)
            if delayNextSave {
                delayNextSave = false
                await withCheckedContinuation { delayedSaveContinuation = $0 }
            }
            if !failures.isEmpty { throw failures.removeFirst() }
            return DriveSaveResult(
                rideId: "ride-1",
                routePath: "rideRoutes/u/ride-1/route.bin",
                alreadySaved: requests.count > 1
            )
        }

        func uploadRoute(_ points: [RecordedDrivePoint], to path: String) async throws {
            uploads.append((path, points))
        }

        func resolveDelayedSave() {
            delayedSaveContinuation?.resume()
            delayedSaveContinuation = nil
        }
    }

    private final class GatedUploadRepository: DriveRecordingRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var continuations: [String: CheckedContinuation<Void, any Error>] = [:]
        private var cancellationRequested: Set<String> = []
        private var started: [String] = []
        private var finished: [String] = []
        private var cancelled: [String] = []

        func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult {
            let sessionId = request.context.sourceSessionId
            return DriveSaveResult(
                rideId: "ride-\(sessionId)",
                routePath: "rideRoutes/u/ride-\(sessionId)/route.bin",
                alreadySaved: false
            )
        }

        func uploadRoute(_ points: [RecordedDrivePoint], to path: String) async throws {
            lock.withLock { started.append(path) }
            do {
                try await withTaskCancellationHandler {
                    try await withCheckedThrowingContinuation { continuation in
                        let cancelImmediately = lock.withLock {
                            if cancellationRequested.contains(path) { return true }
                            continuations[path] = continuation
                            return false
                        }
                        if cancelImmediately {
                            continuation.resume(throwing: CancellationError())
                        }
                    }
                    try Task.checkCancellation()
                } onCancel: {
                    self.cancel(path)
                }
                lock.withLock { finished.append(path) }
            } catch is CancellationError {
                lock.withLock { cancelled.append(path) }
                throw CancellationError()
            }
        }

        func release(_ path: String) {
            let continuation = lock.withLock { continuations.removeValue(forKey: path) }
            continuation?.resume()
        }

        func startedPaths() -> [String] { lock.withLock { started } }
        func finishedPaths() -> [String] { lock.withLock { finished } }
        func cancelledPaths() -> [String] { lock.withLock { cancelled } }

        private func cancel(_ path: String) {
            let continuation = lock.withLock {
                cancellationRequested.insert(path)
                return continuations.removeValue(forKey: path)
            }
            continuation?.resume(throwing: CancellationError())
        }
    }

    func testLiveSessionEndAutoSavesKeepsAndUploadsWithoutPrompt() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        let coordinator = makeCoordinator(repository, provider, clock)
        coordinator.start(context: context())
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 0))
        provider.emitFix(fix(start, offset: 3, latitude: 57.0001))
        await waitUntil { coordinator.state.summary?.pointCount == 2 }

        clock.value = start.addingTimeInterval(10)
        coordinator.endSession(context: context())

        await waitUntil {
            if case .kept(rideId: "ride-1") = coordinator.state { return true }
            return false
        }
        XCTAssertFalse(coordinator.state.presentsSummary)
        XCTAssertEqual(repository.requests.count, 1)
        await waitUntil { repository.uploads.count == 1 }
        XCTAssertEqual(repository.uploads.first?.1, repository.requests.first?.points)
        await waitUntil { provider.activeFixStreamCount == 0 }
    }

    func testTransientAutoSaveRetriesThenFailureRetainsIdempotentDraft() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.failures = Array(repeating: KccFunctionsError(code: .unavailable), count: 3)
        let coordinator = makeCoordinator(repository, provider, clock)
        coordinator.start(context: context())
        clock.value = start.addingTimeInterval(10)
        coordinator.endSession(context: context())
        await waitUntil { coordinator.state.presentsSummary }

        XCTAssertEqual(repository.requests.count, 3)
        XCTAssertEqual(Set(repository.requests.map(\.context.sourceSessionId)), ["session-1"])
        XCTAssertEqual(Set(repository.requests.map(\.endedAt)), [repository.requests[0].endedAt])

        coordinator.retry()
        await waitUntil {
            if case .kept = coordinator.state { return true }
            return false
        }
        XCTAssertEqual(repository.requests.count, 4)
        XCTAssertEqual(repository.requests.last?.context.sourceSessionId, "session-1")
    }

    func testQueuedSessionStartsOnceWhenPreviousSaveCompletes() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.delayNextSave = true
        let coordinator = makeCoordinator(repository, provider, clock)
        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }

        coordinator.endSession(context: context(sessionId: "session-a"))
        await waitUntil { repository.requests.count == 1 }
        // Stream termination is asynchronous. Wait until A's cancelled stream
        // has actually left the provider before asserting that queued B does not
        // open a replacement while A's save gate is still suspended.
        await waitUntil { provider.activeFixStreamCount == 0 }
        coordinator.start(context: context(sessionId: "session-b"))
        XCTAssertEqual(provider.activeFixStreamCount, 0)

        repository.resolveDelayedSave()

        await waitUntil {
            if case .recording = coordinator.state {
                return provider.activeFixStreamCount == 1
            }
            return false
        }
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(provider.activeFixStreamCount, 1)
        XCTAssertEqual(repository.requests.map(\.context.sourceSessionId), ["session-a"])
    }

    func testDirectActiveSessionTransitionSavesAThenRecordsBExactlyOnce() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        let coordinator = makeCoordinator(repository, provider, clock)
        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 0))
        provider.emitFix(fix(start, offset: 3, latitude: 57.0001))
        await waitUntil { coordinator.state.summary?.pointCount == 2 }

        clock.value = start.addingTimeInterval(10)
        coordinator.start(context: context(sessionId: "session-b"))

        await waitUntil {
            if case .recording = coordinator.state {
                return provider.activeFixStreamCount == 1
            }
            return false
        }
        XCTAssertEqual(repository.requests.count, 1)
        XCTAssertEqual(repository.requests[0].context.sourceSessionId, "session-a")
        XCTAssertEqual(repository.requests[0].points.count, 2)
        provider.emitFix(fix(start, offset: 11, latitude: 58))
        await waitUntil { coordinator.state.summary?.pointCount == 1 }
        XCTAssertEqual(provider.activeFixStreamCount, 1)
    }

    func testQueuedSessionWaitsForFailureRetryThenStarts() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.failures = Array(repeating: KccFunctionsError(code: .unavailable), count: 3)
        let coordinator = makeCoordinator(repository, provider, clock)
        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        coordinator.endSession(context: context(sessionId: "session-a"))
        coordinator.start(context: context(sessionId: "session-b"))

        await waitUntil { coordinator.state.presentsSummary }
        XCTAssertEqual(provider.activeFixStreamCount, 0)

        coordinator.retry()

        await waitUntil {
            if case .recording = coordinator.state {
                return provider.activeFixStreamCount == 1
            }
            return false
        }
        XCTAssertEqual(repository.requests.map(\.context.sourceSessionId), [
            "session-a", "session-a", "session-a", "session-a",
        ])
    }

    func testQueuedSessionStartsAfterPermanentFailureIsDiscarded() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.failures = [KccFunctionsError(code: .permissionDenied)]
        let coordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: provider,
            retryWait: { _ in await Task.yield() }
        )
        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        coordinator.endSession(context: context(sessionId: "session-a"))
        coordinator.start(context: context(sessionId: "session-b"))
        await waitUntil { coordinator.state.presentsSummary }

        coordinator.discardFailed()

        await waitUntil {
            if case .recording = coordinator.state {
                return provider.activeFixStreamCount == 1
            }
            return false
        }
        XCTAssertEqual(repository.requests.map(\.context.sourceSessionId), ["session-a"])
    }

    func testEndedQueuedSessionDoesNotImplicitlyRetryFailedDrive() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.failures = Array(repeating: KccFunctionsError(code: .unavailable), count: 3)
        let coordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: provider,
            retryWait: { _ in await Task.yield() }
        )
        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        coordinator.endSession(context: context(sessionId: "session-a"))
        coordinator.start(context: context(sessionId: "session-b"))
        await waitUntil { coordinator.state.presentsSummary }
        let failedState = coordinator.state
        let failedRequestCount = repository.requests.count

        coordinator.endSession(context: context(sessionId: "session-b"))
        for _ in 0..<20 { await Task.yield() }

        XCTAssertEqual(coordinator.state, failedState)
        XCTAssertEqual(repository.requests.count, failedRequestCount)
        coordinator.retry()
        await waitUntil {
            if case .kept = coordinator.state { return true }
            return false
        }
        XCTAssertEqual(provider.activeFixStreamCount, 0)
    }

    func testExpiredQueuedSessionDoesNotImplicitlyRetryFailedDrive() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.failures = Array(repeating: KccFunctionsError(code: .unavailable), count: 3)
        let coordinator = makeCoordinator(repository, provider, clock)
        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        coordinator.endSession(context: context(sessionId: "session-a"))
        coordinator.start(context: context(
            sessionId: "session-b",
            expiresAt: start.addingTimeInterval(5)
        ))
        await waitUntil { coordinator.state.presentsSummary }
        let failedState = coordinator.state
        let failedRequestCount = repository.requests.count

        clock.value = start.addingTimeInterval(6)
        for _ in 0..<50 { await Task.yield() }

        XCTAssertEqual(coordinator.state, failedState)
        XCTAssertEqual(repository.requests.count, failedRequestCount)
        coordinator.retry()
        await waitUntil {
            if case .kept = coordinator.state { return true }
            return false
        }
        XCTAssertEqual(provider.activeFixStreamCount, 0)
    }

    func testConsecutiveDriveUploadsBothFinishWithoutCancellingEarlierUpload() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = GatedUploadRepository()
        let coordinator = DriveRecordingCoordinator(repository: repository, provider: provider)
        let pathA = "rideRoutes/u/ride-session-a/route.bin"
        let pathB = "rideRoutes/u/ride-session-b/route.bin"

        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 0))
        await waitUntil { coordinator.state.summary?.pointCount == 1 }
        coordinator.endSession(context: context(sessionId: "session-a"))
        await waitUntil { repository.startedPaths() == [pathA] }

        coordinator.start(context: context(sessionId: "session-b"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 3, latitude: 58))
        await waitUntil { coordinator.state.summary?.pointCount == 1 }
        coordinator.endSession(context: context(sessionId: "session-b"))
        await waitUntil { Set(repository.startedPaths()) == Set([pathA, pathB]) }

        repository.release(pathB)
        repository.release(pathA)
        await waitUntil { Set(repository.finishedPaths()) == Set([pathA, pathB]) }
        XCTAssertEqual(repository.cancelledPaths(), [])
    }

    func testResetCancelsEveryInFlightRouteUpload() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = GatedUploadRepository()
        let coordinator = DriveRecordingCoordinator(repository: repository, provider: provider)
        let paths = Set([
            "rideRoutes/u/ride-session-a/route.bin",
            "rideRoutes/u/ride-session-b/route.bin",
        ])

        for (index, sessionId) in ["session-a", "session-b"].enumerated() {
            coordinator.start(context: context(sessionId: sessionId))
            await waitUntil { provider.activeFixStreamCount == 1 }
            provider.emitFix(fix(start, offset: TimeInterval(index * 3), latitude: 57 + Double(index)))
            await waitUntil { coordinator.state.summary?.pointCount == 1 }
            coordinator.endSession(context: context(sessionId: sessionId))
            await waitUntil { repository.startedPaths().count == index + 1 }
        }

        coordinator.reset()

        await waitUntil { Set(repository.cancelledPaths()) == paths }
        XCTAssertEqual(repository.finishedPaths(), [])
        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(provider.activeFixStreamCount, 0)
    }

    func testPermissionGrantWhileActiveSessionStartsPendingRecording() async {
        let provider = StubLocationProvider(authorization: .denied)
        let repository = FakeRepository()
        let coordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: provider,
            retryWait: { _ in await Task.yield() }
        )
        coordinator.start(context: context())
        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(provider.activeFixStreamCount, 0)

        provider.setAuthorization(.whileInUse)

        await waitUntil {
            if case .recording = coordinator.state { return true }
            return false
        }
        XCTAssertEqual(provider.activeFixStreamCount, 1)
    }

    func testExpiryWatchdogStopsAndAutoSavesWithoutSessionEmission() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        let coordinator = makeCoordinator(repository, provider, clock)
        coordinator.start(context: context(expiresAt: start.addingTimeInterval(5)))
        await waitUntil { provider.activeFixStreamCount == 1 }

        clock.value = start.addingTimeInterval(6)

        await waitUntil {
            if case .kept = coordinator.state { return true }
            return false
        }
        XCTAssertEqual(repository.requests.count, 1)
        await waitUntil { provider.activeFixStreamCount == 0 }
    }

    func testColdStartJournalWaitsThenResumesWhenActiveSessionArrives() async {
        let url = temporaryJournalURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let journal = FileDriveRecordingJournal(fileURL: url)
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        journal.begin(context: context(), startedAt: start)
        journal.append(point(start, offset: 1))
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = DriveRecordingCoordinator(
            repository: FakeRepository(), provider: provider, journal: journal
        )

        // The shell's pre-snapshot nil performs no reconciliation; the journal
        // remains untouched until an authoritative active session arrives.
        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(journal.restore(sessionId: "session-1")?.points.count, 1)
        coordinator.start(context: context())

        await waitUntil { coordinator.state.summary?.pointCount == 1 }
        XCTAssertEqual(provider.activeFixStreamCount, 1)
    }

    func testAuthoritativeNilRestoresJournalAndAutoSaves() async {
        let url = temporaryJournalURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let journal = FileDriveRecordingJournal(fileURL: url)
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        journal.begin(context: context(), startedAt: start)
        journal.append(point(start, offset: 1))
        let repository = FakeRepository()
        let coordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: StubLocationProvider(authorization: .denied),
            journal: journal,
            now: { start.addingTimeInterval(10) },
            retryWait: { _ in await Task.yield() }
        )

        coordinator.endSession()

        await waitUntil {
            if case .kept = coordinator.state { return true }
            return false
        }
        XCTAssertEqual(repository.requests.first?.points.count, 1)
        XCTAssertNil(journal.restore(sessionId: nil))
    }

    func testUnconfiguredStartStaysIdle() {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = DriveRecordingCoordinator(repository: nil, provider: provider)
        coordinator.start(context: context())
        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(provider.activeFixStreamCount, 0)
    }

    private func makeCoordinator(
        _ repository: FakeRepository,
        _ provider: StubLocationProvider,
        _ clock: Clock
    ) -> DriveRecordingCoordinator {
        DriveRecordingCoordinator(
            repository: repository,
            provider: provider,
            now: { clock.value },
            expiryTickWait: { await Task.yield() },
            retryWait: { _ in await Task.yield() }
        )
    }

    private func context(
        sessionId: String = "session-1",
        expiresAt: Date? = nil
    ) -> DriveRecordingContext {
        DriveRecordingContext(
            sourceSessionId: sessionId,
            vehicleId: "vehicle-1",
            carImagePath: "vehicleImages/u/vehicle-1/cover",
            convoyMembers: [],
            expiresAt: expiresAt
        )
    }

    private func fix(_ start: Date, offset: TimeInterval, latitude: Double = 57) -> LocationFix {
        LocationFix.of(
            latitude: latitude,
            longitude: 12,
            timestamp: start.addingTimeInterval(offset)
        )!
    }

    private func point(_ start: Date, offset: TimeInterval) -> RecordedDrivePoint {
        RecordedDrivePoint(
            latitude: 57,
            longitude: 12,
            timestampMilliseconds: Int64(start.addingTimeInterval(offset).timeIntervalSince1970 * 1_000)
        )
    }

    private func temporaryJournalURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString).routejournal")
    }

    private func waitUntil(
        _ predicate: @escaping @MainActor () -> Bool,
        attempts: Int = 2_000
    ) async {
        for _ in 0..<attempts {
            if predicate() { return }
            await Task.yield()
        }
        XCTFail("condition not reached")
    }

    private final class Clock: @unchecked Sendable {
        var value: Date
        init(_ value: Date) { self.value = value }
    }
}
