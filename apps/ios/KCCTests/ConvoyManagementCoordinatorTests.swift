import XCTest

@testable import KCC

final class ConvoyManagementCoordinatorTests: XCTestCase {
    private final class FakeRepository: ConvoyManagementRepository, @unchecked Sendable {
        private let lock = NSLock()
        var listResults: [ConvoyManagementListResult]
        var listDelaysMilliseconds: [Int]
        private var listCallCount = 0
        var respondResult: ConvoyRespondResult
        var lifecycleResult: ConvoyLifecycleResult
        var inviteResult: ConvoyInviteMutationResult
        var observedConvoys: [ConvoyItem?]
        private(set) var respondCalls: [(String, ConvoyAction)] = []
        private(set) var lifecycleCalls: [(String, ConvoyLifecycleAction)] = []
        private(set) var inviteCalls: [(String, [String])] = []

        init(
            listResults: [ConvoyManagementListResult],
            listDelaysMilliseconds: [Int] = [],
            respondResult: ConvoyRespondResult = .failed(.generic),
            lifecycleResult: ConvoyLifecycleResult = .failed(.generic),
            inviteResult: ConvoyInviteMutationResult = .failed(.generic),
            observedConvoys: [ConvoyItem?] = []
        ) {
            self.listResults = listResults
            self.listDelaysMilliseconds = listDelaysMilliseconds
            self.respondResult = respondResult
            self.lifecycleResult = lifecycleResult
            self.inviteResult = inviteResult
            self.observedConvoys = observedConvoys
        }

        func observeConvoy(convoyId: String) -> AsyncThrowingStream<ConvoyItem?, Error> {
            let values = lock.withLock { observedConvoys }
            return AsyncThrowingStream { continuation in
                values.forEach { continuation.yield($0) }
                continuation.finish()
            }
        }

        func list() async -> ConvoyManagementListResult {
            let (result, delay) = lock.withLock {
                listCallCount += 1
                let result = listResults.isEmpty ? .failed(.generic) : listResults.removeFirst()
                let delay = listDelaysMilliseconds.isEmpty ? 0 : listDelaysMilliseconds.removeFirst()
                return (result, delay)
            }
            if delay > 0 { try? await Task.sleep(for: .milliseconds(delay)) }
            return result
        }

        func recordedListCallCount() -> Int {
            lock.withLock { listCallCount }
        }

        func respond(convoyId: String, action: ConvoyAction) async -> ConvoyRespondResult {
            lock.withLock {
                respondCalls.append((convoyId, action))
                return respondResult
            }
        }

        func lifecycle(
            convoyId: String,
            action: ConvoyLifecycleAction
        ) async -> ConvoyLifecycleResult {
            lock.withLock {
                lifecycleCalls.append((convoyId, action))
                return lifecycleResult
            }
        }

        func invite(
            convoyId: String,
            inviteeUids: [String]
        ) async -> ConvoyInviteMutationResult {
            lock.withLock {
                inviteCalls.append((convoyId, inviteeUids))
                return inviteResult
            }
        }
    }

    @MainActor
    func testLoadPublishesSnapshot() async {
        let snapshot = self.snapshot()
        let coordinator = ConvoyManagementCoordinator(
            repository: FakeRepository(listResults: [.loaded(snapshot)])
        )

        await coordinator.load()

        XCTAssertEqual(coordinator.state, .loaded(snapshot))
    }

    @MainActor
    func testAcceptRefreshesAndReturnsJoinedConvoy() async {
        let invite = item(id: "invite", viewer: .invited)
        let before = snapshot(convoys: [invite], pending: [invite])
        let joined = item(id: "invite", viewer: .accepted)
        let after = snapshot(convoys: [joined])
        let repository = FakeRepository(
            listResults: [.loaded(before), .loaded(after)],
            respondResult: .updated(joined)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let result = await coordinator.respond(convoyId: "invite", action: .accept)

        XCTAssertEqual(result, joined)
        XCTAssertEqual(coordinator.state, .loaded(after))
        XCTAssertEqual(repository.respondCalls.count, 1)
        XCTAssertTrue(coordinator.busyConvoyIds.isEmpty)
    }

    @MainActor
    func testAcceptKeepsCallableStateWhenListRefreshFails() async {
        let invite = item(id: "invite", viewer: .invited)
        let joined = item(id: "invite", viewer: .accepted)
        let repository = FakeRepository(
            listResults: [
                .loaded(snapshot(convoys: [invite], pending: [invite])),
                .failed(.generic)
            ],
            respondResult: .updated(joined)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.respond(convoyId: "invite", action: .accept)

        XCTAssertEqual(coordinator.convoy(id: "invite"), joined)
        XCTAssertEqual(coordinator.activeConvoy, joined)
        XCTAssertEqual(coordinator.snapshot?.pendingInvites, [])
    }

    @MainActor
    func testActiveMembershipBlocksAcceptButNotDecline() async {
        let active = item(id: "active", viewer: .accepted)
        let invite = item(id: "invite", viewer: .invited)
        let before = snapshot(convoys: [active, invite], pending: [invite])
        let after = snapshot(convoys: [active])
        let repository = FakeRepository(
            listResults: [.loaded(before), .loaded(after)],
            respondResult: .updated(invite)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let accepted = await coordinator.respond(convoyId: "invite", action: .accept)
        XCTAssertNil(accepted)
        XCTAssertEqual(coordinator.actionError, .alreadyInConvoy)
        XCTAssertTrue(repository.respondCalls.isEmpty)

        await coordinator.respond(convoyId: "invite", action: .decline)
        XCTAssertEqual(repository.respondCalls.count, 1)
        XCTAssertEqual(repository.respondCalls.first?.1, .decline)
    }

    @MainActor
    func testFailedPreconditionRefreshesAndClassifiesActiveConvoyRace() async {
        let invite = item(id: "invite", viewer: .invited)
        let active = item(id: "other", viewer: .accepted)
        let repository = FakeRepository(
            listResults: [
                .loaded(snapshot(convoys: [invite], pending: [invite])),
                .loaded(snapshot(convoys: [active, invite], pending: [invite]))
            ],
            respondResult: .failed(.unresolvedPrecondition)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.respond(convoyId: "invite", action: .accept)

        XCTAssertEqual(coordinator.actionError, .alreadyInConvoy)
    }

    @MainActor
    func testFailedPreconditionWithoutActiveMembershipClassifiesGoneInvite() async {
        let invite = item(id: "invite", viewer: .invited)
        let repository = FakeRepository(
            listResults: [
                .loaded(snapshot(convoys: [invite], pending: [invite])),
                .loaded(snapshot())
            ],
            respondResult: .failed(.unresolvedPrecondition)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.respond(convoyId: "invite", action: .accept)

        XCTAssertEqual(coordinator.actionError, .inviteGone)
        XCTAssertEqual(coordinator.state, .loaded(snapshot()))
    }

    @MainActor
    func testFailedPreconditionWithIncompleteRefreshStaysUnresolved() async {
        let invite = item(id: "invite", viewer: .invited)
        let incomplete = ConvoyManagementSnapshot(
            convoys: [invite], pendingInvites: [invite], isExhaustive: false
        )
        let repository = FakeRepository(
            listResults: [
                .loaded(snapshot(convoys: [invite], pending: [invite])),
                .loaded(incomplete)
            ],
            respondResult: .failed(.unresolvedPrecondition)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.respond(convoyId: "invite", action: .accept)

        XCTAssertEqual(coordinator.actionError, .unresolvedPrecondition)
    }

    @MainActor
    func testCappedListBlocksAcceptWithoutCallingRepository() async {
        let invite = item(id: "invite", viewer: .invited)
        let capped = ConvoyManagementSnapshot(
            convoys: [invite],
            pendingInvites: [invite],
            isExhaustive: false
        )
        let repository = FakeRepository(listResults: [.loaded(capped)])
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.respond(convoyId: "invite", action: .accept)

        XCTAssertEqual(coordinator.actionError, .generic)
        XCTAssertTrue(repository.respondCalls.isEmpty)
    }

    @MainActor
    func testConfiglessCoordinatorIsUnavailable() async {
        let coordinator = ConvoyManagementCoordinator(repository: nil)
        await coordinator.load()
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testLifecycleMutationRefreshesSnapshot() async {
        let active = item(id: "convoy", viewer: .accepted)
        let ended = item(id: "convoy", status: .ended, viewer: .accepted)
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active])), .loaded(snapshot(convoys: [ended]))],
            lifecycleResult: .updated(ended)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.runLifecycle(convoyId: "convoy", action: .end)

        XCTAssertTrue(succeeded)
        XCTAssertEqual(coordinator.state, .loaded(snapshot(convoys: [ended])))
        XCTAssertEqual(repository.lifecycleCalls.first?.1, .end)
        XCTAssertTrue(coordinator.busyConvoyIds.isEmpty)
    }

    @MainActor
    func testSingleConvoyObservationMergesFreshDocumentState() async {
        let active = item(id: "convoy", viewer: .accepted)
        let ended = item(id: "convoy", status: .ended, viewer: .accepted)
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active]))],
            observedConvoys: [ended]
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.observeConvoy(id: "convoy")

        XCTAssertEqual(coordinator.convoy(id: "convoy"), ended)
        XCTAssertNil(coordinator.activeConvoy)
    }

    @MainActor
    func testLiveObservationSupersedesInFlightListRefresh() async {
        let active = item(id: "convoy", viewer: .accepted)
        let ended = item(id: "convoy", status: .ended, viewer: .accepted)
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active])), .loaded(snapshot(convoys: [active]))],
            listDelaysMilliseconds: [0, 200],
            observedConvoys: [ended]
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let staleRefresh = Task { await coordinator.refresh() }
        while repository.recordedListCallCount() < 2 { await Task.yield() }
        await coordinator.observeConvoy(id: "convoy")
        let refreshSucceeded = await staleRefresh.value
        XCTAssertFalse(refreshSucceeded)

        XCTAssertEqual(coordinator.convoy(id: "convoy"), ended)
    }

    @MainActor
    func testLeavePublishesAndClearsResult() async {
        let active = item(id: "convoy", viewer: .accepted)
        let leaveResult = ConvoyLeaveResult(outcome: .left, newLeaderUid: "next-leader")
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active])), .loaded(snapshot())],
            lifecycleResult: .left(leaveResult)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.runLifecycle(convoyId: "convoy", action: .leave)

        XCTAssertTrue(succeeded)
        XCTAssertEqual(coordinator.lastLeaveResult, leaveResult)
        coordinator.clearLeaveResult()
        XCTAssertNil(coordinator.lastLeaveResult)
    }

    @MainActor
    func testMutationRefreshFailurePreservesLoadedSnapshot() async {
        let active = item(id: "convoy", viewer: .accepted)
        let ended = item(id: "convoy", status: .ended, viewer: .accepted)
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active])), .failed(.generic)],
            lifecycleResult: .updated(ended)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.runLifecycle(convoyId: "convoy", action: .end)

        XCTAssertTrue(succeeded)
        XCTAssertEqual(coordinator.state, .loaded(snapshot(convoys: [ended])))
        XCTAssertNil(coordinator.activeConvoy)
    }

    @MainActor
    func testLeaveRefreshFailureRemovesConvoyLocally() async {
        let active = item(id: "convoy", viewer: .accepted)
        let leaveResult = ConvoyLeaveResult(outcome: .left, newLeaderUid: "next-leader")
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active])), .failed(.generic)],
            lifecycleResult: .left(leaveResult)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.runLifecycle(convoyId: "convoy", action: .leave)

        XCTAssertTrue(succeeded)
        XCTAssertEqual(coordinator.state, .loaded(snapshot()))
        XCTAssertNil(coordinator.convoy(id: "convoy"))
    }

    @MainActor
    func testLateListenerSnapshotDoesNotReinsertDepartedConvoy() async {
        let active = item(id: "convoy", viewer: .accepted)
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active])), .failed(.generic)],
            lifecycleResult: .left(ConvoyLeaveResult(outcome: .left, newLeaderUid: nil)),
            observedConvoys: [active]
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.runLifecycle(convoyId: "convoy", action: .leave)
        await coordinator.observeConvoy(id: "convoy")

        XCTAssertTrue(succeeded)
        XCTAssertNil(coordinator.convoy(id: "convoy"))
    }

    @MainActor
    func testCannotStartRefreshesStaleSnapshot() async {
        let forming = item(id: "convoy", status: .forming, viewer: .accepted)
        let active = item(id: "convoy", viewer: .accepted)
        let repository = FakeRepository(
            listResults: [
                .loaded(snapshot(convoys: [forming])),
                .loaded(snapshot(convoys: [active]))
            ],
            lifecycleResult: .failed(.cannotStart)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.runLifecycle(convoyId: "convoy", action: .start)

        XCTAssertFalse(succeeded)
        XCTAssertEqual(coordinator.actionError, .cannotStart)
        XCTAssertEqual(coordinator.actionError(for: "convoy"), .cannotStart)
        XCTAssertNil(coordinator.actionError(for: "another"))
        XCTAssertEqual(coordinator.state, .loaded(snapshot(convoys: [active])))
    }

    @MainActor
    func testInviteDeduplicatesAndRefreshes() async {
        let active = item(id: "convoy", viewer: .accepted)
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active])), .loaded(snapshot(convoys: [active]))],
            inviteResult: .completed(ConvoyInviteResult(
                convoy: active,
                invitedCount: 2,
                skippedCount: 0
            ))
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.invite(
            convoyId: "convoy",
            inviteeUids: ["b", "a", "b", ""]
        )

        XCTAssertTrue(succeeded)
        XCTAssertEqual(repository.inviteCalls.first?.1, ["a", "b"])
        XCTAssertEqual(
            coordinator.lastInviteResult,
            ConvoyInviteResult(convoy: active, invitedCount: 2, skippedCount: 0)
        )
    }

    @MainActor
    func testInviteKeepsCallableRosterWhenListRefreshFails() async {
        let active = item(id: "convoy", viewer: .accepted)
        let invited = ConvoyMember(
            uid: "friend", displayName: "Friend", role: .member, inviteStatus: .invited
        )
        let updated = ConvoyItem(
            convoyId: "convoy", title: nil, status: .active,
            members: [invited], viewer: active.viewer, createdAt: nil
        )
        let repository = FakeRepository(
            listResults: [.loaded(snapshot(convoys: [active])), .failed(.generic)],
            inviteResult: .completed(ConvoyInviteResult(
                convoy: updated, invitedCount: 1, skippedCount: 0
            ))
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.invite(convoyId: "convoy", inviteeUids: ["friend"])
        XCTAssertTrue(succeeded)
        XCTAssertEqual(coordinator.convoy(id: "convoy"), updated)
    }

    @MainActor
    func testMutationRefreshSupersedesAnOlderPollingResponse() async {
        let active = item(id: "convoy", viewer: .accepted)
        let ended = item(id: "convoy", status: .ended, viewer: .accepted)
        let repository = FakeRepository(
            listResults: [
                .loaded(snapshot(convoys: [active])),
                .loaded(snapshot(convoys: [active])),
                .loaded(snapshot(convoys: [ended]))
            ],
            listDelaysMilliseconds: [0, 200, 0],
            lifecycleResult: .updated(ended)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let staleRefresh = Task { await coordinator.refresh() }
        while repository.recordedListCallCount() < 2 { await Task.yield() }
        let lifecycleSucceeded = await coordinator.runLifecycle(convoyId: "convoy", action: .end)
        XCTAssertTrue(lifecycleSucceeded)
        _ = await staleRefresh.value

        XCTAssertEqual(coordinator.state, .loaded(snapshot(convoys: [ended])))
    }

    @MainActor
    func testInviteRejectsMoreThanCallableBatchLimit() async {
        let active = item(id: "convoy", viewer: .accepted)
        let repository = FakeRepository(listResults: [.loaded(snapshot(convoys: [active]))])
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.invite(
            convoyId: "convoy",
            inviteeUids: (0...ConvoyBarLogic.maximumInviteBatchSize).map { "friend-\($0)" }
        )

        XCTAssertFalse(succeeded)
        XCTAssertEqual(coordinator.actionError, .invalid)
        XCTAssertTrue(repository.inviteCalls.isEmpty)
    }

    @MainActor
    func testInviteStaleFailureRefreshesSnapshot() async {
        let active = item(id: "convoy", viewer: .accepted)
        let ended = item(id: "convoy", status: .ended, viewer: .accepted)
        let repository = FakeRepository(
            listResults: [
                .loaded(snapshot(convoys: [active])),
                .loaded(snapshot(convoys: [ended]))
            ],
            inviteResult: .failed(.unresolvedPrecondition)
        )
        let coordinator = ConvoyManagementCoordinator(repository: repository)
        await coordinator.load()

        let succeeded = await coordinator.invite(convoyId: "convoy", inviteeUids: ["friend"])

        XCTAssertFalse(succeeded)
        XCTAssertEqual(coordinator.actionError, .unresolvedPrecondition)
        XCTAssertEqual(coordinator.state, .loaded(snapshot(convoys: [ended])))
    }

    private func snapshot(
        convoys: [ConvoyItem] = [],
        pending: [ConvoyItem] = []
    ) -> ConvoyManagementSnapshot {
        ConvoyManagementSnapshot(convoys: convoys, pendingInvites: pending, isExhaustive: true)
    }

    private func item(
        id: String,
        status: ConvoyStatus = .active,
        viewer: ConvoyInviteStatus
    ) -> ConvoyItem {
        ConvoyItem(
            convoyId: id,
            title: nil,
            status: status,
            members: [],
            viewer: ConvoyViewer(inviteStatus: viewer),
            createdAt: nil
        )
    }
}
