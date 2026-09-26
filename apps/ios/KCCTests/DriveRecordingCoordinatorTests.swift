import XCTest

@testable import KCC

@MainActor
final class DriveRecordingCoordinatorTests: XCTestCase {
    private final class FakeRepository: DriveRecordingRepository, @unchecked Sendable {
        var requests: [DriveSaveRequest] = []
        var uploads: [(String, [RecordedDrivePoint])] = []
        var error: Error?

        func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult {
            if let error { throw error }
            requests.append(request)
            return DriveSaveResult(
                rideId: "ride-1",
                routePath: "rideRoutes/u/ride-1/route.bin",
                alreadySaved: false
            )
        }

        func uploadRoute(_ points: [RecordedDrivePoint], to path: String) async throws {
            uploads.append((path, points))
        }
    }

    func testSessionRecordsStopsAndExplicitSaveUploadsSamePoints() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = Clock(start)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        let coordinator = DriveRecordingCoordinator(
            repository: repository,
            provider: provider,
            now: { clock.value }
        )
        coordinator.start(context: context())
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 0))
        provider.emitFix(fix(start, offset: 3, latitude: 57.0001))
        await waitUntil { coordinator.state.summary?.pointCount == 2 }
        clock.value = start.addingTimeInterval(10)
        coordinator.stop()
        XCTAssertTrue(coordinator.state.presentsSummary)
        await coordinator.save(title: "My drive")
        guard case .saved(_, let rideId) = coordinator.state else {
            return XCTFail("expected saved")
        }
        XCTAssertEqual(rideId, "ride-1")
        XCTAssertEqual(repository.requests.first?.points.count, 2)
        XCTAssertEqual(repository.uploads.first?.0, "rideRoutes/u/ride-1/route.bin")
        XCTAssertEqual(repository.uploads.first?.1, repository.requests.first?.points)
        await waitUntil { provider.activeFixStreamCount == 0 }
    }

    func testDiscardMakesNoWriteAndReleasesExactRoute() async {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        let coordinator = DriveRecordingCoordinator(repository: repository, provider: provider)
        coordinator.start(context: context())
        await waitUntil { provider.activeFixStreamCount == 1 }
        provider.emitFix(fix(start, offset: 0))
        await waitUntil { coordinator.state.summary?.pointCount == 1 }
        coordinator.stop()
        coordinator.discard()
        XCTAssertEqual(coordinator.state, .discarded)
        XCTAssertTrue(repository.requests.isEmpty)
        coordinator.start(context: context(sessionId: "session-2"))
        await waitUntil { provider.activeFixStreamCount == 1 }
        XCTAssertEqual(coordinator.state.summary?.pointCount, 0)
    }

    func testFailureRetainsDraftForIdempotentRetry() async {
        let provider = StubLocationProvider(authorization: .whileInUse)
        let repository = FakeRepository()
        repository.error = KccFunctionsError(code: .unavailable)
        let coordinator = DriveRecordingCoordinator(repository: repository, provider: provider)
        coordinator.start(context: context())
        coordinator.stop()
        await coordinator.save(title: nil)
        guard case .failed(_, let code) = coordinator.state else {
            return XCTFail("expected failed")
        }
        XCTAssertEqual(code, .unavailable)
        repository.error = nil
        await coordinator.save(title: nil)
        XCTAssertEqual(repository.requests.count, 1)
        guard case .saved = coordinator.state else { return XCTFail("expected retry success") }
    }

    func testUnconfiguredAndUnauthorizedStartsStayIdle() {
        let provider = StubLocationProvider(authorization: .denied)
        let unavailable = DriveRecordingCoordinator(repository: nil, provider: provider)
        unavailable.start(context: context())
        XCTAssertEqual(unavailable.state, .idle)

        let configured = DriveRecordingCoordinator(repository: FakeRepository(), provider: provider)
        configured.start(context: context())
        XCTAssertEqual(configured.state, .idle)
        XCTAssertEqual(provider.activeFixStreamCount, 0)
    }

    func testStoppedSessionRestoresJournalIntoForcedChoice() {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString).routejournal")
        defer { try? FileManager.default.removeItem(at: url) }
        let journal = FileDriveRecordingJournal(fileURL: url)
        let context = context()
        let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
        journal.begin(context: context, startedAt: startedAt)
        journal.append(
            RecordedDrivePoint(
                latitude: 57,
                longitude: 12,
                timestampMilliseconds: 1_700_000_001_000
            )
        )
        let coordinator = DriveRecordingCoordinator(
            repository: FakeRepository(),
            provider: StubLocationProvider(authorization: .denied),
            journal: journal,
            now: { startedAt.addingTimeInterval(10) }
        )
        coordinator.restorePending(context: context)
        guard case .prompt(let summary) = coordinator.state else {
            return XCTFail("expected restored prompt")
        }
        XCTAssertEqual(summary.pointCount, 1)
        XCTAssertEqual(summary.durationSeconds, 10)
    }

    private func context(sessionId: String = "session-1") -> DriveRecordingContext {
        DriveRecordingContext(
            sourceSessionId: sessionId,
            vehicleId: "vehicle-1",
            carImagePath: "vehicleImages/u/vehicle-1/cover",
            convoyMembers: []
        )
    }

    private func fix(_ start: Date, offset: TimeInterval, latitude: Double = 57) -> LocationFix {
        LocationFix.of(
            latitude: latitude,
            longitude: 12,
            timestamp: start.addingTimeInterval(offset)
        )!
    }

    private func waitUntil(
        _ predicate: @escaping @MainActor () -> Bool,
        attempts: Int = 100
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
