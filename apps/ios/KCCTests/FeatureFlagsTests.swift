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

    func testChatFlagTransitionOnlyDisablesEventChat() {
        let enabled = FeatureFlags.resolve(from: ["chat": true])
        let disabled = FeatureFlags.resolve(from: ["chat": false])

        XCTAssertTrue(ChatFeatureGate.eventChatEnabled(
            flags: enabled,
            access: .unrestrictedCommunity
        ))
        XCTAssertFalse(ChatFeatureGate.eventChatEnabled(
            flags: disabled,
            access: .unrestrictedCommunity
        ))
        XCTAssertTrue(ChatFeatureGate.channelAndDirectChatEnabled(
            access: .unrestrictedCommunity
        ))
    }

    func testSocialSharingFlagControlsDriveShareActions() {
        XCTAssertTrue(FeatureGate.isAvailable(
            flags: .contractDefaults, flag: .socialSharing, memberGated: false,
            access: .unrestrictedCommunity
        ))
        XCTAssertFalse(FeatureGate.isAvailable(
            flags: FeatureFlags.resolve(from: ["socialSharing": false]),
            flag: .socialSharing,
            memberGated: false,
            access: .unrestrictedCommunity
        ))
    }
}
