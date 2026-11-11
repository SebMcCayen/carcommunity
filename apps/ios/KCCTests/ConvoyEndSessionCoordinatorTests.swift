import Foundation
import XCTest
@testable import KCC

@MainActor
final class ConvoyEndSessionCoordinatorTests: XCTestCase {
    func testRTDBStopBeforeConvoyEndWaitsForChoiceThenContinuesOneDrive() async {
        let h = Harness()
        defer { h.stop() }
        await h.update(h.autoSession())
        await wait { h.convoys.hasObserver && h.provider.activeFixStreamCount >= 2 }
        h.provider.emitFix(LocationFix.of(latitude: 57, longitude: 12, timestamp: h.clock.date)!)
        h.provider.emitFix(LocationFix.of(latitude: 57.0001, longitude: 12,
            timestamp: h.clock.date.addingTimeInterval(3))!)
        await wait { h.recording.state.summary?.pointCount == 2 }
        h.convoys.emit(h.convoy(.active))
        await h.update(h.autoSession(.stopped))
        XCTAssertFalse(h.coordinator.presentsChoice)
        XCTAssertTrue(h.saves.requests.isEmpty)
        h.convoys.emit(h.convoy(.ended))
        await wait { h.coordinator.presentsChoice }
        await h.coordinator.chooseContinue()
        XCTAssertTrue(h.coordinator.isContinuing)
        XCTAssertTrue(h.saves.requests.isEmpty)
        await h.update(h.soloSession())
        XCTAssertFalse(h.coordinator.presentsChoice)
        XCTAssertFalse(h.coordinator.isContinuing)
        XCTAssertNotNil(h.recording.distanceMeters(for: "solo"))
        await h.update(h.soloSession(.stopped))
        await wait { h.saves.requests.count == 1 }
        XCTAssertEqual(h.saves.requests.first?.context.sourceSessionId, "auto")
        XCTAssertEqual(h.saves.requests.first?.points.count, 2)
    }

    func testConvoyEndBeforeRTDBStopOffersEndAndSavesOnce() async {
        let h = Harness()
        defer { h.stop() }
        await h.update(h.autoSession())
        await wait { h.convoys.hasObserver }
        h.convoys.emit(h.convoy(.ended))
        await wait { h.coordinator.presentsChoice }
        h.coordinator.chooseEnd()
        await wait { h.saves.requests.count == 1 }
        await h.update(h.autoSession(.stopped))
        XCTAssertFalse(h.coordinator.presentsChoice)
        XCTAssertEqual(h.saves.requests.count, 1)
    }

    func testPrivacyStopAndOwnConvoyExitNeverOfferContinuation() async {
        for exitingConvoy in [false, true] {
            let h = Harness()
            await h.update(h.autoSession())
            await wait { h.convoys.hasObserver }
            if exitingConvoy { h.exiting.value = true }
            else { _ = await h.live.stopSharing() }
            h.convoys.emit(h.convoy(.ended))
            h.coordinator.tick()
            await wait { h.saves.requests.count == 1 }
            XCTAssertFalse(h.coordinator.presentsChoice)
            h.stop()
        }
    }

    func testMissingEndDocumentTimesOutAndCannotOfferLateContinuation() async {
        let h = Harness()
        defer { h.stop() }
        await h.update(h.autoSession())
        await wait { h.convoys.hasObserver }
        await h.update(h.autoSession(.stopped))
        h.clock.date = h.clock.date.addingTimeInterval(21)
        h.coordinator.tick()
        await wait { h.saves.requests.count == 1 }
        h.convoys.emit(h.convoy(.ended))
        await h.update(h.autoSession(.stopped))
        XCTAssertFalse(h.coordinator.presentsChoice)
        XCTAssertEqual(h.saves.requests.count, 1)
    }

    func testFailedContinuationAndMissingManualEchoSaveRatherThanOrphanRecorder() async {
        for startFails in [true, false] {
            let h = Harness()
            await h.update(h.autoSession())
            await wait { h.convoys.hasObserver }
            h.convoys.emit(h.convoy(.ended))
            await wait { h.coordinator.presentsChoice }
            await h.update(h.autoSession(.stopped))
            h.liveRepository.failStart = startFails
            await h.coordinator.chooseContinue()
            if !startFails {
                h.clock.date = h.clock.date.addingTimeInterval(21)
                h.coordinator.tick()
            }
            await wait { h.saves.requests.count == 1 }
            XCTAssertTrue(h.coordinator.commandFailed)
            XCTAssertFalse(h.coordinator.isContinuing)
            h.stop()
        }
    }

    func testExpiryAndUnrelatedReplacementDoNotInheritOldChoice() async {
        let h = Harness()
        defer { h.stop() }
        await h.update(h.autoSession())
        await wait { h.convoys.hasObserver }
        h.clock.date = h.clock.date.addingTimeInterval(3_601)
        h.coordinator.tick()
        await wait { h.saves.requests.count == 1 }
        XCTAssertFalse(h.coordinator.presentsChoice)
        await h.update(h.soloSession())
        h.convoys.emit(h.convoy(.ended))
        XCTAssertFalse(h.coordinator.presentsChoice)
        XCTAssertFalse(h.coordinator.isContinuing)
    }

    func testTeardownRejectsLateSessionAndConvoyCallbacks() async {
        let h = Harness()
        defer { h.stop() }
        await h.update(h.autoSession())
        await wait { h.convoys.hasObserver }
        h.coordinator.stop()
        h.recording.reset()
        h.convoys.emit(h.convoy(.ended))
        await h.update(h.autoSession())
        XCTAssertFalse(h.coordinator.presentsChoice)
        XCTAssertEqual(h.recording.state, .idle)
        XCTAssertTrue(h.saves.requests.isEmpty)
    }

    func testFailedExitKeepsRecordingAndCanStillOfferRemoteEndChoice() async {
        let h = Harness()
        defer { h.stop() }
        await h.update(h.autoSession())
        await wait { h.convoys.hasObserver }
        h.exiting.pending = true
        h.coordinator.tick()
        XCTAssertTrue(h.saves.requests.isEmpty)
        XCTAssertNotNil(h.recording.state.summary)
        h.convoys.emit(h.convoy(.ended))
        await h.update(h.autoSession(.stopped))
        XCTAssertFalse(h.coordinator.presentsChoice)
        XCTAssertTrue(h.saves.requests.isEmpty)
        h.exiting.pending = false
        h.coordinator.tick()
        await wait { h.coordinator.presentsChoice }
        XCTAssertTrue(h.saves.requests.isEmpty)
    }

    func testFailedContinuationWithStillActiveSessionCanRetryWithoutSaving() async {
        let h = Harness()
        defer { h.stop() }
        await h.update(h.autoSession())
        await wait { h.convoys.hasObserver }
        h.convoys.emit(h.convoy(.ended))
        await wait { h.coordinator.presentsChoice }
        h.liveRepository.failStart = true
        await h.coordinator.chooseContinue()
        XCTAssertTrue(h.coordinator.commandFailed)
        XCTAssertTrue(h.coordinator.presentsChoice)
        XCTAssertTrue(h.saves.requests.isEmpty)
        XCTAssertNotNil(h.recording.state.summary)
    }

    func testQueuedEndCommandCannotRunAfterIdentityTeardown() async {
        let h = Harness()
        await h.update(h.autoSession())
        await wait { h.convoys.hasObserver }
        h.convoys.emit(h.convoy(.ended))
        await wait { h.coordinator.presentsChoice }
        h.coordinator.chooseEnd()
        h.stop()
        // Let the queued stop task reach its dispatch fence after teardown.
        try? await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(h.liveRepository.stopCount, 0)
    }

    private func wait(_ condition: @MainActor () -> Bool) async {
        let deadline = Date().addingTimeInterval(3)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }

    private final class Clock: @unchecked Sendable { var date = Date(timeIntervalSince1970: 1_000) }
    private final class Exit { var value = false; var pending = false }

    @MainActor private final class Harness {
        let clock = Clock()
        let exiting = Exit()
        let convoys = Convoys()
        let liveRepository = Live()
        let saves = Saves()
        let provider = StubLocationProvider(authorization: .whileInUse)
        let live: LiveLocationCoordinator
        let recording: DriveRecordingCoordinator
        let coordinator: ConvoyEndSessionCoordinator

        init() {
            let clock = self.clock, exiting = self.exiting
            live = LiveLocationCoordinator(repository: liveRepository, provider: provider, now: { clock.date })
            recording = DriveRecordingCoordinator(repository: saves, provider: provider, now: { clock.date })
            coordinator = ConvoyEndSessionCoordinator(repository: convoys, live: live, recording: recording,
                localExit: { _ in exiting.value }, pendingExit: { _ in exiting.pending }, now: { clock.date })
            live.start()
        }

        func update(_ session: LiveSessionInfo) async {
            let revision = live.sessionSnapshotRevision
            liveRepository.emit(session)
            let deadline = Date().addingTimeInterval(3)
            while live.sessionSnapshotRevision == revision, Date() < deadline {
                try? await Task.sleep(for: .milliseconds(1))
            }
            XCTAssertGreaterThan(live.sessionSnapshotRevision, revision)
            coordinator.update(session: session, context: DriveRecordingContext(
                sourceSessionId: session.sessionId, vehicleId: nil, carImagePath: nil,
                convoyMembers: [], expiresAt: session.expiresAt))
        }

        func autoSession(_ status: LiveSessionStatus = .active) -> LiveSessionInfo {
            .init(sessionId: "auto", status: status, duration: .sixHours,
                  expiresAt: Date(timeIntervalSince1970: 4_600), convoyId: "convoy", convoyAutoStarted: true)
        }
        func soloSession(_ status: LiveSessionStatus = .active) -> LiveSessionInfo {
            .init(sessionId: "solo", status: status, duration: .sixHours,
                  expiresAt: clock.date.addingTimeInterval(21_600))
        }
        func convoy(_ status: ConvoyStatus) -> ConvoyItem {
            .init(convoyId: "convoy", title: nil, status: status,
                  members: [.init(uid: "me", displayName: nil, role: .member, inviteStatus: .accepted)],
                  viewer: .init(inviteStatus: .accepted, role: .member), createdAt: nil)
        }
        func stop() { coordinator.stop(); live.standDownForRestrictedAccess(); recording.reset() }
    }

    @MainActor private final class Saves: DriveRecordingRepository {
        var requests: [DriveSaveRequest] = []
        func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult {
            requests.append(request)
            return .init(rideId: "ride", routePath: nil, alreadySaved: false)
        }
        func uploadRoute(_ points: [RecordedDrivePoint], to path: String) async throws {}
    }

    private final class Live: LiveLocationRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: AsyncStream<LiveSessionInfo?>.Continuation?
        private var stops = 0
        var stopCount: Int { lock.withLock { stops } }
        var failStart = false
        func startSession(duration: LiveSessionDuration, vehicleId: String?) async throws {
            if failStart { throw KccFunctionsError(code: .unavailable) }
        }
        func updatePosition(_ coordinate: LiveCoordinate) async throws {}
        func stopSession() async throws { lock.withLock { stops += 1 } }
        func hideMeNow() async throws {}
        func currentUserId() -> String? { "me" }
        func ownSessionUpdates(uid: String) -> AsyncStream<LiveSessionInfo?> {
            AsyncStream { sink in lock.withLock { continuation = sink } }
        }
        func emit(_ session: LiveSessionInfo?) { lock.withLock { continuation }?.yield(session) }
    }

    private final class Convoys: ConvoyManagementRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: AsyncThrowingStream<ConvoyItem?, Error>.Continuation?
        var hasObserver: Bool { lock.withLock { continuation != nil } }
        func observeConvoy(convoyId: String) -> AsyncThrowingStream<ConvoyItem?, Error> {
            AsyncThrowingStream { sink in lock.withLock { continuation = sink } }
        }
        func emit(_ convoy: ConvoyItem?) { lock.withLock { continuation }?.yield(convoy) }
        func list() async -> ConvoyManagementListResult { .failed(.generic) }
        func respond(convoyId: String, action: ConvoyAction) async -> ConvoyRespondResult { .failed(.generic) }
        func lifecycle(convoyId: String, action: ConvoyLifecycleAction) async -> ConvoyLifecycleResult { .failed(.generic) }
        func invite(convoyId: String, inviteeUids: [String]) async -> ConvoyInviteMutationResult { .failed(.generic) }
    }
}
