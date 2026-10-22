import XCTest
@testable import KCC

final class GroupDriveTests: XCTestCase {
    func testJoinGateRequiresPublishedEventAndGoingOrMaybeRsvp() {
        XCTAssertTrue(GroupDrive.canJoin(eventStatus: .published, rsvp: .going))
        XCTAssertTrue(GroupDrive.canJoin(eventStatus: .published, rsvp: .maybe))
        XCTAssertFalse(GroupDrive.canJoin(eventStatus: .published, rsvp: .notGoing))
        XCTAssertFalse(GroupDrive.canJoin(eventStatus: .cancelled, rsvp: .going))
    }

    func testActiveParticipantsRemoveLeftAndSortByDisplayName() {
        let participants = [
            GroupDriveParticipant(uid: "3", displayName: "Zoe", status: .arrived),
            GroupDriveParticipant(uid: "2", displayName: "Adam", status: .left),
            GroupDriveParticipant(uid: "1", displayName: "Ada", status: .joined),
        ]

        XCTAssertEqual(GroupDrive.activeParticipants(participants).map(\.uid), ["1", "3"])
    }

    @MainActor
    func testCoordinatorObservesRosterAndOwnStatus() async {
        let repository = FakeGroupDriveRepository()
        let coordinator = GroupDriveCoordinator(
            repository: repository,
            eventId: "event",
            uid: "me",
            canJoin: true
        )
        coordinator.start()

        repository.emitRoster(.loaded([
            GroupDriveParticipant(uid: "me", displayName: "Me", status: .joined),
            GroupDriveParticipant(uid: "left", displayName: "Left", status: .left),
        ]))
        repository.emitStatus(.loaded(.joined))

        await waitFor {
            !coordinator.isLoading
                && coordinator.isParticipating
                && coordinator.activeParticipantUids == ["me"]
        }
        XCTAssertEqual(coordinator.activeParticipantUids, ["me"])
    }

    @MainActor
    func testCoordinatorFailsClosedWhenRosterListenerFails() async {
        let repository = FakeGroupDriveRepository()
        let coordinator = GroupDriveCoordinator(
            repository: repository,
            eventId: "event",
            uid: "me",
            canJoin: true
        )
        coordinator.start()
        repository.emitRoster(.loaded([
            GroupDriveParticipant(uid: "other", displayName: "Other", status: .joined),
        ]))
        await waitFor { coordinator.activeParticipantUids == ["other"] }

        repository.emitRoster(.failed(code: "PERMISSION_DENIED"))
        await waitFor { coordinator.loadFailed }

        XCTAssertTrue(coordinator.activeParticipantUids.isEmpty)
    }

    @MainActor
    func testCoordinatorSerializesCommandsAndReportsFailure() async {
        let repository = FakeGroupDriveRepository()
        repository.commandError = TestFailure.failed
        let coordinator = GroupDriveCoordinator(
            repository: repository,
            eventId: "event",
            uid: "me",
            canJoin: true
        )

        coordinator.join()
        coordinator.leave()
        await waitFor { coordinator.actionState == .failed }

        XCTAssertEqual(repository.commands, ["join:event"])
        coordinator.clearFailure()
        XCTAssertEqual(coordinator.actionState, .idle)
    }

    @MainActor
    private func waitFor(
        timeout: TimeInterval = 1,
        _ condition: @escaping @MainActor () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            await Task.yield()
        }
        XCTAssertTrue(condition())
    }
}

private enum TestFailure: Error { case failed }

private final class FakeGroupDriveRepository: GroupDriveRepository, @unchecked Sendable {
    private var rosterContinuation: AsyncStream<GroupDriveRosterSnapshot>.Continuation?
    private var statusContinuation: AsyncStream<GroupDriveOwnStatusSnapshot>.Continuation?
    var commandError: Error?
    private(set) var commands: [String] = []

    func participants(eventId: String) -> AsyncStream<GroupDriveRosterSnapshot> {
        AsyncStream { rosterContinuation = $0 }
    }

    func myStatus(eventId: String, uid: String) -> AsyncStream<GroupDriveOwnStatusSnapshot> {
        AsyncStream { statusContinuation = $0 }
    }

    func join(eventId: String) async throws {
        commands.append("join:\(eventId)")
        if let commandError { throw commandError }
    }

    func updateStatus(eventId: String, status: GroupDriveStatus) async throws {
        commands.append("status:\(eventId):\(status.rawValue)")
        if let commandError { throw commandError }
    }

    func leave(eventId: String) async throws {
        commands.append("leave:\(eventId)")
        if let commandError { throw commandError }
    }

    func emitRoster(_ snapshot: GroupDriveRosterSnapshot) {
        rosterContinuation?.yield(snapshot)
    }

    func emitStatus(_ snapshot: GroupDriveOwnStatusSnapshot) {
        statusContinuation?.yield(snapshot)
    }
}
