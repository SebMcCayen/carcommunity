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

    let carCommunitySuspended: Bool
    let blacklisted: Bool
    let restrictionReason: String?
    let productVersion: Int

    init(role: UserRole, activeMember: Bool, suspended: Bool, deleted: Bool,
         carCommunitySuspended: Bool = false, blacklisted: Bool = false,
         restrictionReason: String? = nil, productVersion: Int = 0) {
        self.role = role; self.activeMember = activeMember
        self.suspended = suspended; self.deleted = deleted
        self.carCommunitySuspended = carCommunitySuspended; self.blacklisted = blacklisted
        self.restrictionReason = restrictionReason; self.productVersion = productVersion
    }

    func withProduct(_ map: [String: Any]) -> AccountAccess? {
        guard map["initialized"] as? Bool == true else { return nil }
        let restricted = map["suspended"] as? Bool == true
        return AccountAccess(role: role, activeMember: activeMember, suspended: suspended, deleted: deleted,
            carCommunitySuspended: restricted,
            blacklisted: map["explicitBlacklistId"] is String || map["inheritedBlocked"] as? Bool == true,
            restrictionReason: map[restricted ? "suspensionReason" : "blacklistReason"] as? String,
            productVersion: (map["version"] as? NSNumber)?.intValue ?? 0)
    }

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

    var isRestricted: Bool { suspended || deleted || carCommunitySuspended || blacklisted }
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
