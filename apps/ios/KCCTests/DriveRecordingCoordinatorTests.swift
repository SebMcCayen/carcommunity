import XCTest

@testable import KCC

@MainActor
final class DriveRecordingCoordinatorTests: XCTestCase {
    // Assertions and the delayed-save gate share the test's actor. Otherwise
    // requests.append can become visible before the continuation is installed,
    // and resolveDelayedSave can lose the release on a different executor.
    @MainActor private final class FakeRepository: DriveRecordingRepository {
        var requests: [DriveSaveRequest] = []
        var uploads: [(String, [RecordedDrivePoint])] = []
        var failures: [Error] = []
        var uploadFailures: [Error] = []
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
            if !uploadFailures.isEmpty { throw uploadFailures.removeFirst() }
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
        private let onReady: @Sendable (GatedUploadRepository, String) -> Void

        init(onReady: @escaping @Sendable (GatedUploadRepository, String) -> Void = { _, _ in }) {
            self.onReady = onReady
        }

        func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult {
            let sessionId = request.context.sourceSessionId
            return DriveSaveResult(
                rideId: "ride-\(sessionId)",
                routePath: "rideRoutes/u/ride-\(sessionId)/route.bin",
                alreadySaved: false
            )
        }

        func uploadRoute(_ points: [RecordedDrivePoint], to path: String) async throws {
            do {
                try await withTaskCancellationHandler {
                    try await withCheckedThrowingContinuation { continuation in
                        let cancelImmediately = lock.withLock {
                            // Publish readiness atomically with the gate. A test
                            // observing startedPaths may immediately release it.
                            // Publishing first used to lose that release when
                            // this worker was descheduled before installation.
                            started.append(path)
                            if cancellationRequested.contains(path) { return true }
                            continuations[path] = continuation
                            return false
                        }
                        onReady(self, path)
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
        XCTAssertNotNil(coordinator.distanceMeters(for: context().sourceSessionId))
        XCTAssertNil(coordinator.distanceMeters(for: "replacement-session"))

        clock.value = start.addingTimeInterval(10)
        coordinator.endSession(context: context())

        await waitUntil {
            if case .kept(rideId: "ride-1") = coordinator.state { return true }
            return false
        }
        XCTAssertFalse(coordinator.state.presentsSummary)
        XCTAssertNil(coordinator.distanceMeters(for: context().sourceSessionId))
        XCTAssertEqual(repository.requests.count, 1)
        await waitUntil { repository.uploads.count == 1 }
        XCTAssertEqual(repository.uploads.first?.1, repository.requests.first?.points)
        await waitUntil { provider.activeFixStreamCount == 0 }
    }

    func testFailedRouteUploadPersistsAndRetriesOnNextCoordinatorStartup() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = FileDriveRecordingJournal(
            fileURL: directory.appendingPathComponent("active.routejournal")
        )
        let spool = journal.routeUploadSpool
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.uploadFailures = [KccFunctionsError(code: .unavailable)]
        let coordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: provider,
            journal: journal,
            uploadSpool: spool,
            now: { start.addingTimeInterval(10) },
            retryWait: { _ in await Task.yield() }
        )
        coordinator.start(context: context())
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 1))
        await waitUntil { coordinator.state.summary?.pointCount == 1 }

        coordinator.endSession(context: context())

        await waitUntil { repository.uploads.count == 1 }
        XCTAssertNil(journal.restore(sessionId: nil))
        XCTAssertEqual(spool.pendingUploads().count, 1)

        let retryCoordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: StubLocationProvider(authorization: .denied),
            journal: journal,
            uploadSpool: spool
        )

        await waitUntil { repository.uploads.count == 2 }
        await waitUntil { spool.pendingUploads().isEmpty }
        withExtendedLifetime(retryCoordinator) {}
        XCTAssertEqual(repository.uploads[0].0, repository.uploads[1].0)
        XCTAssertEqual(repository.uploads[0].1, repository.uploads[1].1)
    }

    func testSignOutRetainsPendingUploadButAccountDeletionPurgesIt() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = FileDriveRecordingJournal(
            fileURL: directory.appendingPathComponent("active.routejournal")
        )
        let spool = journal.routeUploadSpool
        let upload = PendingDriveRouteUpload(
            path: "rideRoutes/u/ride-1/route.bin",
            points: [RecordedDrivePoint(latitude: 57, longitude: 12, timestampMilliseconds: 123)]
        )
        XCTAssertTrue(spool.store(path: upload.path, points: upload.points))
        let coordinator = DriveRecordingCoordinator(
            repository: nil,
            provider: StubLocationProvider(authorization: .denied),
            journal: journal,
            uploadSpool: spool
        )

        coordinator.reset()
        XCTAssertEqual(spool.pendingUploads(), [upload])

        journal.begin(context: context(), startedAt: Date(timeIntervalSince1970: 100))
        journal.append(upload.points[0])
        coordinator.purgeForAccountDeletion()

        XCTAssertTrue(spool.pendingUploads().isEmpty)
        XCTAssertNil(journal.restore(sessionId: nil))
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
        await waitUntil { provider.activeFixStreamCount == 0 }
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

    func testInvalidArgumentCannotRetryAndDiscardClearsExactRouteJournal() async {
        let url = temporaryJournalURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let journal = FileDriveRecordingJournal(fileURL: url)
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.failures = [KccFunctionsError(code: .invalidArgument)]
        let coordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: provider,
            journal: journal,
            now: { start.addingTimeInterval(10) },
            retryWait: { _ in await Task.yield() }
        )
        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 1))
        await waitUntil { coordinator.state.summary?.pointCount == 1 }

        coordinator.endSession(context: context(sessionId: "session-a"))
        await waitUntil { coordinator.state.presentsSummary }
        XCTAssertEqual(journal.restore(sessionId: "session-a")?.points.count, 1)
        XCTAssertFalse(coordinator.state.canRetrySave)

        coordinator.retry()
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(repository.requests.count, 1)

        coordinator.discardFailed()

        XCTAssertEqual(coordinator.state, .discarded)
        XCTAssertNil(journal.restore(sessionId: nil))
        XCTAssertEqual(provider.activeFixStreamCount, 0)
    }

    func testMalformedResponseDiscardClearsOldRouteAndStartsQueuedSession() async {
        let url = temporaryJournalURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let journal = FileDriveRecordingJournal(fileURL: url)
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.failures = [DriveRecordingRepositoryError.malformedResponse]
        let coordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: provider,
            journal: journal,
            now: { start.addingTimeInterval(10) },
            retryWait: { _ in await Task.yield() }
        )
        coordinator.start(context: context(sessionId: "session-a"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 1))
        await waitUntil { coordinator.state.summary?.pointCount == 1 }
        coordinator.endSession(context: context(sessionId: "session-a"))
        coordinator.start(context: context(sessionId: "session-b"))
        await waitUntil { coordinator.state.presentsSummary }
        XCTAssertFalse(coordinator.state.canRetrySave)

        coordinator.discardFailed()

        await waitUntil {
            if case .recording = coordinator.state {
                return provider.activeFixStreamCount == 1
            }
            return false
        }
        XCTAssertEqual(repository.requests.map(\.context.sourceSessionId), ["session-a"])
        XCTAssertNil(journal.restore(sessionId: "session-a"))
        XCTAssertEqual(journal.restore(sessionId: "session-b")?.points, [])
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

    func testUploadReadinessCanBeReleasedBeforeTheUploaderSuspends() async {
        // Synchronously release the moment readiness is published. This covers
        // the scheduling window behind the consecutive-upload CI failure without
        // depending on a lucky worker preemption or an arbitrary delay.
        let repository = GatedUploadRepository(onReady: { repository, path in repository.release(path) })
        let path = "rideRoutes/u/ready-gate/route.bin"
        let upload = Task {
            try await repository.uploadRoute([point(Date(timeIntervalSince1970: 1_700_000_000), offset: 0)], to: path)
        }
        defer { upload.cancel() }
        await waitUntil { repository.finishedPaths() == [path] }
        XCTAssertEqual(repository.startedPaths(), [path])
        XCTAssertEqual(repository.cancelledPaths(), [])
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

    func testConvoyToSoloPreservesOneDriveAndRecoversContinuationFromJournal() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        let url = temporaryJournalURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let journal = FileDriveRecordingJournal(fileURL: url)
        let coordinator = DriveRecordingCoordinator(repository: repository, provider: provider,
            journal: journal, now: { clock.value })
        coordinator.start(context: context(sessionId: "convoy"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 0))
        provider.emitFix(fix(start, offset: 3, latitude: 57.0001))
        await waitUntil { coordinator.state.summary?.pointCount == 2 }
        coordinator.continueAsSingle(from: "convoy", context: context(sessionId: "solo"))
        coordinator.start(context: context(sessionId: "solo"))
        XCTAssertEqual(coordinator.state.summary?.pointCount, 2)
        XCTAssertNotNil(coordinator.distanceMeters(for: "solo"))
        XCTAssertNil(coordinator.distanceMeters(for: "convoy"))
        XCTAssertEqual(journal.restore(sessionId: "solo")?.context.sourceSessionId, "convoy")
        XCTAssertTrue(repository.requests.isEmpty)
        coordinator.reset()

        // reset intentionally deletes a live journal. Model a process death by
        // restoring the protected journal into a fresh owner-scoped instance.
        journal.begin(context: context(sessionId: "convoy").continuing(with: context(sessionId: "solo")), startedAt: start)
        journal.append(point(start, offset: 0))
        journal.append(RecordedDrivePoint(latitude: 57.0001, longitude: 12,
            timestampMilliseconds: Int64(start.addingTimeInterval(3).timeIntervalSince1970 * 1_000)))
        let resumed = DriveRecordingCoordinator(repository: repository, provider: provider,
            journal: journal, now: { clock.value })
        resumed.start(context: context(sessionId: "solo"))
        XCTAssertEqual(resumed.state.summary?.pointCount, 2)
        clock.value = start.addingTimeInterval(20)
        resumed.endSession(context: context(sessionId: "solo"))
        await waitUntil { repository.requests.count == 1 }
        XCTAssertEqual(repository.requests.first?.context.sourceSessionId, "convoy")
        XCTAssertEqual(repository.requests.first?.points.count, 2)
        resumed.reset()
    }

    func testRejectedRecordingEventsReachSaveAndRetriesKeepTheSameDiagnostics() async throws {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.failures = [KccFunctionsError(code: .unavailable)]
        let coordinator = makeCoordinator(repository, provider, clock)
        coordinator.start(context: context())
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitRecordingEvent(RecordingLocationEvent(decision: .accepted(fix(start, offset: 0)),
            receivedUptime: 100))
        provider.emitRecordingEvent(RecordingLocationEvent(decision: .rejected(.accuracy), receivedUptime: 120))
        provider.emitRecordingEvent(RecordingLocationEvent(decision: .accepted(
            fix(start, offset: 30, latitude: 57.0001)), receivedUptime: 130))
        await waitUntil { coordinator.state.summary?.pointCount == 2 }
        clock.value = start.addingTimeInterval(40)
        coordinator.endSession(context: context())
        await waitUntil { repository.requests.count == 2 }
        let first = try XCTUnwrap(repository.requests.first)
        XCTAssertEqual(first, repository.requests[1])
        XCTAssertEqual(first.recordingDiagnostics?.receivedPoints, 3)
        XCTAssertEqual(first.recordingDiagnostics?.acceptedPoints, 2)
        XCTAssertEqual(first.recordingDiagnostics?.rejectedAccuracy, 1)
        XCTAssertEqual(first.recordingDiagnostics?.gapCount, 1)
        XCTAssertEqual(first.points.count, 2)
        coordinator.reset()
    }

    func testRecordingVersionIsCheckpointedBeforeFirstFixAndPreservedAfterAppUpgrade() async throws {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = FileDriveRecordingJournal(fileURL: directory.appendingPathComponent("active.routejournal"))
        let repository = FakeRepository()
        let original = DriveRecordingCoordinator(repository: repository,
            provider: StubLocationProvider(authorization: .whileInUse), journal: journal,
            recordingAppVersion: "1.2.3", now: { start })
        original.start(context: context())
        let checkpoint = try XCTUnwrap(journal.restore(sessionId: context().sourceSessionId))
        XCTAssertEqual(checkpoint.recordingDiagnostics?.platform, "ios")
        XCTAssertEqual(checkpoint.recordingDiagnostics?.appVersion, "1.2.3")
        XCTAssertEqual(checkpoint.points.count, 0)

        // A new process reads the persisted file after the installed app changed.
        let resumed = DriveRecordingCoordinator(repository: repository,
            provider: StubLocationProvider(authorization: .whileInUse), journal: journal,
            recordingAppVersion: "2.0.0", now: { start.addingTimeInterval(60) })
        resumed.start(context: context())
        resumed.endSession(context: context())
        await waitUntil { repository.requests.count == 1 }
        XCTAssertEqual(repository.requests.first?.recordingDiagnostics?.platform, "ios")
        XCTAssertEqual(repository.requests.first?.recordingDiagnostics?.appVersion, "1.2.3")
        XCTAssertEqual(repository.requests.first?.recordingDiagnostics?.resumedCount, 1)
        original.reset()
        resumed.reset()
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
        attempts: Int = 2_000,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        for _ in 0..<attempts {
            if predicate() { return }
            await Task.yield()
        }
        XCTFail("condition not reached", file: file, line: line)
    }

    private final class Clock: @unchecked Sendable {
        var value: Date
        init(_ value: Date) { self.value = value }
    }
}
