import Foundation
import Testing
@testable import KCC

struct MemberProfileTests {
    @Test func relationshipPrecedenceUsesFriendshipBeforeStaleRequests() {
        let data = FriendsData(
            friends: [FriendSummary(uid: "target", displayName: nil, avatarPath: nil, friendsSince: nil)],
            incoming: [Self.request(id: "incoming", from: "target", to: "me", direction: .incoming)],
            outgoing: [Self.request(id: "outgoing", from: "me", to: "target", direction: .outgoing)]
        )
        #expect(MemberRelationship.resolve(data, targetUid: "target") == .friends)
    }

    @Test func incomingRelationshipCarriesAuthoritativeRequestId() {
        let data = FriendsData(
            friends: [],
            incoming: [Self.request(id: "r-42", from: "target", to: "me", direction: .incoming)],
            outgoing: []
        )
        #expect(MemberRelationship.resolve(data, targetUid: "target") == .incomingPending(requestId: "r-42"))
    }

    @Test func socialLinksRejectStoredUrlsAndBuildOnlyOwnedHosts() {
        let unsafe = Self.profile(facebook: "https://evil.example/name", instagram: "safe_name", youtube: "Creator-1")
        let links = MemberSocialLinks.links(unsafe)
        #expect(links.map { $0.label } == ["Instagram", "YouTube"])
        #expect(links.map { $0.url.host } == ["www.instagram.com", "www.youtube.com"])
        #expect(links.last?.url.path == "/@Creator-1")
    }

    @MainActor
    @Test func outgoingBlockShortCircuitsAllPublicReads() async {
        let repository = FakeMemberProfileRepository(result: .loaded(Self.content()))
        let blocking = FakeBlockingRepository(blocked: [BlockedUser(
            userId: "target", displayName: nil, blockedAt: nil
        )])
        let coordinator = MemberProfileCoordinator(
            targetUid: "target", viewerUid: "me", repository: repository,
            friends: nil, blocking: blocking
        )

        await coordinator.load()

        #expect(coordinator.state == MemberProfileState.blocked)
        #expect(repository.loadCount == 0)
    }

    @MainActor
    @Test func successfulUnblockSkipsPotentiallyStaleBlockSnapshot() async {
        let repository = FakeMemberProfileRepository(result: .loaded(Self.content()))
        let blocking = FakeBlockingRepository(blocked: [BlockedUser(
            userId: "target", displayName: nil, blockedAt: nil
        )])
        let coordinator = MemberProfileCoordinator(
            targetUid: "target", viewerUid: "me", repository: repository,
            friends: nil, blocking: blocking
        )
        await coordinator.load()
        await coordinator.unblock()

        #expect(coordinator.state == MemberProfileState.loaded(Self.content()))
        #expect(repository.loadCount == 1)
        #expect(blocking.unblocked == ["target"])
    }

    @MainActor
    @Test func failedFriendActionDoesNotInventRelationship() async {
        let friends = FakeMemberFriendsRepository(
            listResult: .loaded(FriendsData(friends: [], incoming: [], outgoing: [])),
            sendResult: .failed(.notAddable)
        )
        let coordinator = MemberProfileCoordinator(
            targetUid: "target", viewerUid: "me",
            repository: FakeMemberProfileRepository(result: .loaded(Self.content())),
            friends: friends, blocking: nil
        )
        await coordinator.load()
        await coordinator.addFriend()

        #expect(coordinator.relationship == .none)
        #expect(coordinator.actionError == FriendActionError.notAddable)
    }

    @MainActor
    @Test func refreshFailureDoesNotRetainStaleFriendAuthorization() async {
        let friends = FakeMemberFriendsRepository(
            listResult: .loaded(FriendsData(
                friends: [FriendSummary(
                    uid: "target", displayName: nil, avatarPath: nil, friendsSince: nil
                )],
                incoming: [], outgoing: []
            )),
            sendResult: .failed(.generic)
        )
        let coordinator = MemberProfileCoordinator(
            targetUid: "target", viewerUid: "me",
            repository: FakeMemberProfileRepository(result: .loaded(Self.content())),
            friends: friends, blocking: nil
        )
        await coordinator.load()
        #expect(coordinator.relationship == .friends)

        friends.listResult = .failed(.network)
        await coordinator.load()

        #expect(coordinator.relationship == .unknown)
    }

    private static func request(
        id: String, from: String, to: String, direction: FriendRequestDirection
    ) -> FriendRequestSummary {
        FriendRequestSummary(
            requestId: id, fromUid: from, toUid: to, direction: direction,
            otherUser: FriendUser(uid: from == "me" ? to : from, displayName: nil, avatarPath: nil),
            createdAt: nil
        )
    }

    private static func profile(
        facebook: String? = nil, instagram: String? = nil, youtube: String? = nil
    ) -> MemberProfile {
        MemberProfile(
            uid: "target", displayName: "Member", bio: nil, avatarPath: nil,
            facebook: facebook, instagram: instagram, youtube: youtube, createdAt: nil
        )
    }

    private static func content() -> MemberProfileContent {
        MemberProfileContent(profile: Self.profile(), vehicles: [], badges: .available([]), pointsBalance: 0)
    }
}

private final class FakeMemberProfileRepository: MemberProfileRepository, @unchecked Sendable {
    let result: MemberProfileResult
    private(set) var loadCount = 0
    init(result: MemberProfileResult) { self.result = result }
    func load(targetUid: String) async -> MemberProfileResult { loadCount += 1; return result }
    func imageDownloadURL(for path: String) async -> URL? { nil }
}

private final class FakeBlockingRepository: BlockingRepository, @unchecked Sendable {
    let blocked: [BlockedUser]
    private(set) var unblocked: [String] = []
    init(blocked: [BlockedUser]) { self.blocked = blocked }
    func observeBlocked(uid: String) -> AsyncStream<BlockedUsersSnapshot> {
        AsyncStream { continuation in
            continuation.yield(.loaded(blocked))
            continuation.finish()
        }
    }
    func block(targetUserId: String) async throws {}
    func unblock(targetUserId: String) async throws { unblocked.append(targetUserId) }
}

private final class FakeMemberFriendsRepository: FriendsRepository, @unchecked Sendable {
    var listResult: FriendsResult
    let sendResult: SendRequestResult
    init(listResult: FriendsResult, sendResult: SendRequestResult) {
        self.listResult = listResult
        self.sendResult = sendResult
    }
    func list() async -> FriendsResult { listResult }
    func sendRequest(nickname: String) async -> SendRequestResult { sendResult }
    func sendRequest(toUid: String) async -> SendRequestResult { sendResult }
    func respond(requestId: String, accept: Bool) async -> RespondResult { accept ? .accepted : .declined }
    func cancelRequest(toUid: String) async -> CancelResult { .cancelled }
    func remove(friendUid: String) async -> RemoveResult { .removed }
}
