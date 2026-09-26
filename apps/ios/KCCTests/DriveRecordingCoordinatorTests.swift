import XCTest

@testable import KCC

@MainActor
final class DriveRecordingCoordinatorTests: XCTestCase {
    private final class FakeRepository: DriveRecordingRepository, @unchecked Sendable {
        var requests: [DriveSaveRequest] = []
        var uploads: [(String, [RecordedDrivePoint])] = []
        var failures: [Error] = []

        func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult {
            requests.append(request)
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
