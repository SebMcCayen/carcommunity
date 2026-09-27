import XCTest
@testable import KCC

final class FeatureFlagsTests: XCTestCase {
    func testRegistryKeysAndDefaultsMirrorContract() {
        XCTAssertEqual(Set(FeatureFlag.allCases.map(\.rawValue)), [
            "liveLocation", "chat", "crownHunt", "crownHuntSpawn", "partners",
            "partnerStats", "pushNotifications", "socialSharing", "externalDataSources",
            "digitalBillboards", "partnerInsightsPassBy", "crownHuntPerks",
            "crownHuntLiveShareScoring", "reportTicketsBrowser", "chatReplies",
            "eventDetailsRequirePaid", "partnerMemberOffersRequirePaid",
            "crownHuntRequirePaid"
        ])
        let defaults = FeatureFlags.contractDefaults
        for flag in FeatureFlag.allCases {
            XCTAssertEqual(defaults.isEnabled(flag), flag.contractDefault)
        }
    }

    func testStoredBooleansOverlayDefaultsAndMalformedValuesDoNot() {
        let flags = FeatureFlags.resolve(from: [
            "chat": false,
            "crownHuntPerks": true,
            "liveLocation": "false"
        ])
        XCTAssertFalse(flags.isEnabled(.chat))
        XCTAssertTrue(flags.isEnabled(.crownHuntPerks))
        XCTAssertTrue(flags.isEnabled(.liveLocation))
    }

    func testFeatureGateCombinesFlagMembershipAndRestriction() {
        let flags = FeatureFlags.resolve(from: ["chat": false])
        XCTAssertFalse(FeatureGate.isAvailable(
            flags: flags, flag: .chat, memberGated: false,
            access: .unrestrictedCommunity
        ))
        XCTAssertTrue(FeatureGate.isAvailable(
            flags: .contractDefaults, flag: .liveLocation, memberGated: true,
            access: .unrestrictedCommunity
        ))
        XCTAssertFalse(FeatureGate.isAvailable(
            flags: .contractDefaults, flag: .liveLocation, memberGated: false,
            access: AccountAccess(
                role: .user, activeMember: true, suspended: true, deleted: false
            )
        ))
    }
}
