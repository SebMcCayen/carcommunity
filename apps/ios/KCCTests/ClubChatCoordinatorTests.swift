import XCTest
@testable import KCC

@MainActor
final class ClubChatCoordinatorTests: XCTestCase {
    private final class Repository: ClubChatRepository {
        var continuation: AsyncStream<ClubChatSnapshot?>.Continuation?
        var operations: [String] = []
        var sendFails = true
        var suspended: CheckedContinuation<Void, any Error>?
        var suspendSend = false
        var actions: [String] = []
        var pageRequests = 0
        func updates() -> AsyncStream<ClubChatSnapshot?> {
            AsyncStream { continuation = $0 }
        }
        func loadOlder(membership: ClubChatMembership) async throws { pageRequests += 1 }
        func send(membership: ClubChatMembership, text: String, operationID: String) async throws {
            operations.append(operationID)
            if suspendSend { try await withCheckedThrowingContinuation { suspended = $0 } }
            if sendFails { throw URLError(.notConnectedToInternet) }
        }
        func manage(membership: ClubChatMembership, action: String, messageID: String, reason: String?) async throws {
            actions.append(action + ":" + (reason ?? ""))
        }
        func block(authorID: String) async throws { actions.append("block:" + authorID) }
    }
    private func proof(_ club: String = "club", role: String = "member", joined: TimeInterval = 1) -> ClubChatMembership {
        ClubChatMembership(clubID: club, role: role, joinedAt: Date(timeIntervalSince1970: joined))
    }
    private func state(_ membership: ClubChatMembership, messages: [ChannelMessage] = [], older: Bool = false) -> ClubChatSnapshot {
        ClubChatSnapshot(membership: membership, messages: messages, hasOlder: older)
    }
    private func wait(_ predicate: () -> Bool) async {
        for _ in 0..<1000 {
            if predicate() { return }
            try? await Task.sleep(for: .milliseconds(1))
        }
        XCTFail("Timed out waiting for coordinator")
    }
    func testNoProofCannotSendAndRevocationClearsPrivateState() async {
        let repo = Repository(), coordinator = ClubChatCoordinator(repository: nil, uid: "no-proof")
        XCTAssertNil(coordinator.send("secret"))
        XCTAssertTrue(repo.operations.isEmpty)
        coordinator.apply(state(proof(), messages: [ChannelMessage(id: "one", senderUid: "other", text: "private")]))
        coordinator.draft = "unsent private draft"
        coordinator.apply(nil)
        XCTAssertNil(coordinator.snapshot)
        XCTAssertTrue(coordinator.messages.isEmpty)
        XCTAssertEqual(coordinator.draft, "")
    }
    func testStreamStartsOnceAndStopRejectsLateDelivery() async {
        let repo = Repository(), coordinator = ClubChatCoordinator(repository: repo, uid: "lifecycle")
        coordinator.start(); coordinator.start()
        repo.continuation?.yield(state(proof()))
        await wait { coordinator.snapshot != nil }
        coordinator.stop()
        repo.continuation?.yield(state(proof()))
        await Task.yield()
        XCTAssertNil(coordinator.snapshot)
    }
    func testAmbiguousSendRetryReusesOperationAndReconcilesServerID() async throws {
        let repo = Repository(), coordinator = ClubChatCoordinator(repository: repo, uid: "retry-user")
        coordinator.apply(state(proof()))
        coordinator.draft = " hello 👑 "
        coordinator.sendDraft()
        XCTAssertEqual(coordinator.draft, "")
        await wait { coordinator.pending.first?.deliveryState == .failed }
        let failed = try XCTUnwrap(coordinator.pending.first)
        repo.sendFails = false
        coordinator.send(failed.text, retry: failed)
        await wait { repo.operations.count == 2 }
        XCTAssertEqual(repo.operations[0], repo.operations[1])
        XCTAssertEqual(failed.id, ClubChatCoordinator.messageID(uid: "retry-user", operationID: repo.operations[0]))
        coordinator.apply(state(proof(), messages: [ChannelMessage(id: failed.id, senderUid: "retry-user", text: "hello 👑")]))
        XCTAssertTrue(coordinator.pending.isEmpty)
        XCTAssertEqual(coordinator.messages.count, 1)
    }
    func testOldSendCannotRepopulateARejoinedRoom() async {
        let repo = Repository(), coordinator = ClubChatCoordinator(repository: repo, uid: "rejoin")
        repo.suspendSend = true
        coordinator.apply(state(proof()))
        coordinator.send("old room")
        await wait { repo.suspended != nil }
        coordinator.apply(nil)
        coordinator.apply(state(proof(joined: 2)))
        coordinator.draft = "new room"
        repo.suspended?.resume(throwing: URLError(.notConnectedToInternet)); repo.suspended = nil
        await Task.yield()
        XCTAssertTrue(coordinator.pending.isEmpty)
        XCTAssertFalse(coordinator.failed)
        XCTAssertEqual(coordinator.draft, "new room")
    }
    func testRemovedMessagesCannotBeActedOnAndMembersCannotModerate() async {
        let repo = Repository(), coordinator = ClubChatCoordinator(repository: repo, uid: "actions")
        let message = ChannelMessage(id: "one", senderUid: "other", text: "hello")
        coordinator.apply(state(proof(), messages: [message]))
        await coordinator.act("removeMessage", message: message)
        XCTAssertTrue(repo.actions.isEmpty)
        await coordinator.act("reportMessage", message: message, reason: .unsafeDriving)
        XCTAssertEqual(repo.actions, ["reportMessage:dangerousAdvice"])
        coordinator.apply(state(proof()))
        await coordinator.act("block", message: message)
        XCTAssertEqual(repo.actions.count, 1)
    }
    func testPaginationWorksEvenWhenEveryMessageIsFilteredOut() async {
        let repo = Repository(), coordinator = ClubChatCoordinator(repository: repo, uid: "paging")
        coordinator.apply(state(proof(), older: true))
        await coordinator.loadOlder()
        XCTAssertEqual(repo.pageRequests, 1)
        coordinator.apply(nil)
        await coordinator.loadOlder()
        XCTAssertEqual(repo.pageRequests, 1)
    }
    func testServerMessageIDMatchesAndroidGoldenVector() {
        XCTAssertEqual(ClubChatCoordinator.messageID(uid: "member", operationID: "operation-1234567890"),
                       "32ea56a8a9b8d1279ebe6f6b36746816cea3293ab4aa963d9e57488b2070d476")
    }
}
