import Foundation

enum UserRole: String, CaseIterable, Sendable {
    case user
    case admin
    case owner
}

/// Backend-managed access fields from `users/{uid}`. This value only drives
/// presentation; Firestore rules and callables remain the enforcement boundary.
struct AccountAccess: Equatable, Sendable {
    let role: UserRole
    let activeMember: Bool
    let suspended: Bool
    let deleted: Bool

    static let unrestrictedCommunity = AccountAccess(
        role: .user, activeMember: false, suspended: false, deleted: false
    )

    static func fromMap(_ map: [String: Any]) -> AccountAccess {
        AccountAccess(
            role: (map["role"] as? String).flatMap(UserRole.init(rawValue:)) ?? .user,
            activeMember: map["activeMember"] as? Bool ?? false,
            suspended: map["suspended"] as? Bool ?? false,
            deleted: map["deleted"] as? Bool ?? false
        )
    }

    var isRestricted: Bool { suspended || deleted }
    var canAccessMemberFeatures: Bool { activeMember && !isRestricted }
    var canAccessAdminFeatures: Bool {
        (role == .admin || role == .owner) && !isRestricted
    }
    var hasBackendAccess: Bool { canAccessAdminFeatures || canAccessMemberFeatures }
}

/// Legacy blanket member gate. Keep disabled: approved paid capabilities use
/// their own narrow entitlement checks. Restriction always closes this gate.
enum MemberGating {
    static let enabled = false

    static func allows(access: AccountAccess) -> Bool {
        !access.isRestricted && (!enabled || access.canAccessMemberFeatures)
    }

    /// Compatibility overload for existing pure feature tests/callers. It has
    /// no account-status input, so session-level code must use `allows(access:)`.
    static func allows(isActiveMember: Bool) -> Bool {
        !enabled || isActiveMember
    }
}

enum AccountAccessSnapshot: Equatable, Sendable {
    case loaded(AccountAccess?)
    case failed(code: String?)
}
