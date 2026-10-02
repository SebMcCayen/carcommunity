import Foundation
import Observation

@MainActor
@Observable
final class MemberProfileCoordinator {
    private let targetUid: String
    private let viewerUid: String
    private let repository: MemberProfileRepository?
    private let friends: FriendsRepository?
    private let blocking: BlockingRepository?

    private(set) var state: MemberProfileState = .loading
    private(set) var relationship: MemberRelationship = .unknown
    private(set) var action: MemberProfileAction?
    private(set) var actionError: FriendActionError?
    private(set) var moderationFailed = false
    private(set) var avatarURL: URL?

    init(
        targetUid: String,
        viewerUid: String,
        repository: MemberProfileRepository?,
        friends: FriendsRepository?,
        blocking: BlockingRepository?
    ) {
        self.targetUid = targetUid
        self.viewerUid = viewerUid
        self.repository = repository
        self.friends = friends
        self.blocking = blocking
    }

    var canModerate: Bool {
        blocking != nil && !targetUid.isEmpty && targetUid != viewerUid
    }

    func load(consultBlockList: Bool = true) async {
        guard action == nil, let repository, !targetUid.isEmpty else {
            if repository == nil || targetUid.isEmpty { state = .unavailable }
            return
        }
        state = .loading
        // A refresh must not keep action affordances derived from an older
        // friend graph. If the new list fails, unknown is the only honest
        // authorization state; callable enforcement remains the final guard.
        relationship = .unknown
        actionError = nil
        avatarURL = nil
        if consultBlockList, await viewerHasBlockedTarget() {
            state = .blocked
            return
        }

        async let profileResult = repository.load(targetUid: targetUid)
        async let friendResult = friends?.list()
        let (profile, graph) = await (profileResult, friendResult)
        if case .loaded(let data) = graph {
            relationship = MemberRelationship.resolve(data, targetUid: targetUid)
        }
        switch profile {
        case .loaded(let content):
            state = .loaded(content)
            if let path = content.profile.avatarPath {
                avatarURL = await repository.imageDownloadURL(for: path)
            }
        case .notFound: state = .unavailable
        case .failed: state = .failed
        }
    }

    func addFriend() async {
        await runFriend(.addFriend) { friends in
            switch await friends.sendRequest(toUid: targetUid) {
            case .requested: return .success(.outgoingPending)
            case .nowFriends: return .success(.friends)
            case .ambiguous: return .failure(.generic)
            case .failed(let error): return .failure(error)
            }
        }
    }

    func cancelRequest() async {
        await runFriend(.cancelRequest) { friends in
            switch await friends.cancelRequest(toUid: targetUid) {
            case .cancelled: return .success(.none)
            case .failed(let error): return .failure(error)
            }
        }
    }

    func respond(accept: Bool) async {
        guard case .incomingPending(let requestId) = relationship else { return }
        await runFriend(accept ? .acceptRequest : .declineRequest) { friends in
            switch await friends.respond(requestId: requestId, accept: accept) {
            case .accepted: return .success(.friends)
            case .declined: return .success(.none)
            case .failed(let error): return .failure(error)
            }
        }
    }

    func unfriend() async {
        await runFriend(.unfriend) { friends in
            switch await friends.remove(friendUid: targetUid) {
            case .removed: return .success(.none)
            case .failed(let error): return .failure(error)
            }
        }
    }

    func block() async {
        guard canModerate, action == nil, let blocking else { return }
        action = .block
        moderationFailed = false
        do {
            try await blocking.block(targetUserId: targetUid)
            state = .blocked
            relationship = .unknown
        } catch is CancellationError {
        } catch {
            moderationFailed = true
        }
        action = nil
    }

    func unblock() async {
        guard canModerate, action == nil, let blocking else { return }
        action = .unblock
        moderationFailed = false
        do {
            try await blocking.unblock(targetUserId: targetUid)
            action = nil
            await load(consultBlockList: false)
            return
        } catch is CancellationError {
        } catch {
            moderationFailed = true
        }
        action = nil
    }

    func clearErrors() {
        actionError = nil
        moderationFailed = false
    }

    private func runFriend(
        _ nextAction: MemberProfileAction,
        operation: (FriendsRepository) async -> Result<MemberRelationship, FriendActionError>
    ) async {
        guard action == nil, let friends else { return }
        action = nextAction
        actionError = nil
        let result = await operation(friends)
        switch result {
        case .success(let settled):
            relationship = settled
            // Reconcile only after success. If this read fails, the known
            // successful mutation remains the best available state.
            if case .loaded(let data) = await friends.list() {
                relationship = MemberRelationship.resolve(data, targetUid: targetUid)
            }
        case .failure(let error): actionError = error
        }
        action = nil
    }

    private func viewerHasBlockedTarget() async -> Bool {
        guard let blocking, !viewerUid.isEmpty else { return false }
        for await snapshot in blocking.observeBlocked(uid: viewerUid) {
            switch snapshot {
            case .loaded(let users): return users.contains { $0.userId == targetUid }
            case .failed: return false
            }
        }
        return false
    }
}
