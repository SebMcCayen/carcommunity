import Foundation

/// A caller-owned block row from `userBlocks/{uid}/blocked/{targetUid}`.
/// The direction is intentionally implicit: this model can only represent
/// users the caller blocked and can never reveal who blocked the caller.
struct BlockedUser: Equatable, Identifiable, Sendable {
    let userId: String
    let displayName: String?
    let blockedAt: Date?

    var id: String { userId }

    /// Tolerant Firestore-independent decoder. The document id is the trusted
    /// fallback for legacy rows whose denormalized blockedUserId is absent.
    static func decode(documentId: String, fields: [String: Any]) -> BlockedUser? {
        let fallback = documentId.trimmingCharacters(in: .whitespacesAndNewlines)
        let stored = (fields["blockedUserId"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let userId = (stored?.isEmpty == false ? stored : fallback), !userId.isEmpty else {
            return nil
        }
        let rawName = (fields["displayName"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return BlockedUser(
            userId: userId,
            displayName: rawName?.isEmpty == false ? rawName : nil,
            blockedAt: fields["createdAt"] as? Date
        )
    }
}

enum BlockedUsers {
    /// Newest blocks first. Undated legacy rows sort last, with stable user-id
    /// ordering so repeated snapshots never shuffle equal rows.
    static func sortedForList(_ users: [BlockedUser]) -> [BlockedUser] {
        users.sorted { lhs, rhs in
            switch (lhs.blockedAt, rhs.blockedAt) {
            case let (left?, right?) where left != right: return left > right
            case (_?, nil): return true
            case (nil, _?): return false
            default: return lhs.userId < rhs.userId
            }
        }
    }
}

enum BlockedUsersSnapshot: Equatable, Sendable {
    case loaded([BlockedUser])
    /// Bare Firestore status only; never an SDK message containing paths or ids.
    case failed(code: String?)
}

enum BlockedUsersUiState: Equatable, Sendable {
    case loading
    case unavailable
    case empty
    case loaded([BlockedUser])
    case failed(code: String?)
}

enum BlockActionStatus: Equatable, Sendable {
    case idle
    case working(targetUserId: String)
    case succeeded
    case failed(code: KccFunctionsErrorCode?)

    var isWorking: Bool {
        if case .working = self { return true }
        return false
    }
}
