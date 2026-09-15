import XCTest

@testable import KCC

final class ConvoyManagementCoordinatorTests: XCTestCase {
    private final class FakeRepository: ConvoyManagementRepository, @unchecked Sendable {
        private let lock = NSLock()
        var listResults: [ConvoyManagementListResult]
        var respondResult: ConvoyRespondResult
        private(set) var respondCalls: [(String, ConvoyAction)] = []

        init(
            listResults: [ConvoyManagementListResult],
            respondResult: ConvoyRespondResult = .failed(.generic)
        ) {
            self.listResults = listResults
            self.respondResult = respondResult
        }

        func list() async -> ConvoyManagementListResult {
            lock.withLock {
                listResults.isEmpty ? .failed(.generic) : listResults.removeFirst()
            }
        }

        func respond(convoyId: String, action: ConvoyAction) async -> ConvoyRespondResult {
            lock.withLock {
                respondCalls.append((convoyId, action))
                return respondResult
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

    private func snapshot(
        convoys: [ConvoyItem] = [],
        pending: [ConvoyItem] = []
    ) -> ConvoyManagementSnapshot {
        ConvoyManagementSnapshot(convoys: convoys, pendingInvites: pending, isExhaustive: true)
    }

    private func item(id: String, viewer: ConvoyInviteStatus) -> ConvoyItem {
        ConvoyItem(
            convoyId: id,
            title: nil,
            status: .active,
            members: [],
            viewer: ConvoyViewer(inviteStatus: viewer),
            createdAt: nil
        )
    }
}
