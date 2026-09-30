import Foundation

/// Public, read-only projection of another member. Owner-only account and
/// entitlement fields are intentionally absent.
struct MemberProfile: Equatable, Sendable {
    let uid: String
    let displayName: String?
    let bio: String?
    let avatarPath: String?
    let facebook: String?
    let instagram: String?
    let youtube: String?
    let createdAt: Date?

    static func fromMap(uid: String, map: [String: Any]) -> MemberProfile? {
        guard !uid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return MemberProfile(
            uid: uid,
            displayName: map["displayName"] as? String,
            bio: map["bio"] as? String,
            avatarPath: map["avatarPath"] as? String,
            facebook: map["facebook"] as? String,
            instagram: map["instagram"] as? String,
            youtube: map["youtube"] as? String,
            createdAt: map["createdAt"] as? Date
        )
    }
}

enum PublicBadges: Equatable, Sendable {
    case available([Badge])
    case unavailable
    case failed
}

struct MemberProfileContent: Equatable, Sendable {
    let profile: MemberProfile
    let vehicles: [Vehicle]
    let badges: PublicBadges
    let pointsBalance: Int64
}

enum MemberProfileResult: Equatable, Sendable {
    case loaded(MemberProfileContent)
    case notFound
    case failed
}

enum MemberProfileState: Equatable, Sendable {
    case loading
    /// The viewer's own outgoing block is known. No target data is read.
    case blocked
    case unavailable
    case failed
    case loaded(MemberProfileContent)
}

enum MemberRelationship: Equatable, Sendable {
    case unknown
    case none
    case outgoingPending
    case incomingPending(requestId: String)
    case friends

    static func resolve(_ data: FriendsData, targetUid: String) -> MemberRelationship {
        if data.friends.contains(where: { $0.uid == targetUid }) { return .friends }
        if let request = data.incoming.first(where: { $0.fromUid == targetUid }) {
            return .incomingPending(requestId: request.requestId)
        }
        if data.outgoing.contains(where: { $0.toUid == targetUid }) { return .outgoingPending }
        return .none
    }
}

enum MemberProfileAction: Equatable, Sendable {
    case addFriend, cancelRequest, acceptRequest, declineRequest, unfriend, block, unblock
}

