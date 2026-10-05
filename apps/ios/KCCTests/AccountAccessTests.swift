import XCTest
@testable import KCC

final class AccountAccessTests: XCTestCase {
    func testDecodingUsesSafeDefaultsAndAcceptsKnownRoles() {
        XCTAssertEqual(AccountAccess.fromMap([:]), .unrestrictedCommunity)
        XCTAssertEqual(
            AccountAccess.fromMap([
                "role": "owner", "activeMember": true,
                "suspended": false, "deleted": false
            ]),
            AccountAccess(role: .owner, activeMember: true, suspended: false, deleted: false)
        )
        XCTAssertEqual(AccountAccess.fromMap(["role": "superuser"]).role, .user)
    }

    func testSuspensionAndDeletionOverrideMemberAndAdminAccess() {
        for restricted in [
            AccountAccess(role: .owner, activeMember: true, suspended: true, deleted: false),
            AccountAccess(role: .admin, activeMember: true, suspended: false, deleted: true)
        ] {
            XCTAssertTrue(restricted.isRestricted)
            XCTAssertFalse(restricted.canAccessMemberFeatures)
            XCTAssertFalse(restricted.canAccessAdminFeatures)
            XCTAssertFalse(restricted.hasBackendAccess)
            XCTAssertFalse(MemberGating.allows(access: restricted))
        }
    }

    func testAdminBypassAndMemberGateMatchBackendSemantics() {
        let admin = AccountAccess(
            role: .admin, activeMember: false, suspended: false, deleted: false
        )
        XCTAssertTrue(admin.canAccessAdminFeatures)
        XCTAssertTrue(admin.hasBackendAccess)
        XCTAssertFalse(admin.canAccessMemberFeatures)

        XCTAssertFalse(MemberGating.enabled)
        XCTAssertTrue(MemberGating.allows(access: .unrestrictedCommunity))
    }

    @MainActor
    func testAccessStateIsFencedToBoundIdentity() {
        let session = AppAccessSession(accessRepository: nil, flagsRepository: nil)
        session.bind(uid: "first")

        XCTAssertEqual(session.accountState(for: "first"), .unavailable)
        XCTAssertEqual(session.accountState(for: "replacement"), .loading)
    }
}
