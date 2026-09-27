import Foundation

/// Exact mirror of `contracts/features/feature-flags.json` and Android's
/// `config/FeatureFlags.kt`.
enum FeatureFlag: String, CaseIterable, Sendable {
    case liveLocation, chat, crownHunt, crownHuntSpawn, partners, partnerStats
    case pushNotifications, socialSharing, externalDataSources, digitalBillboards
    case partnerInsightsPassBy, crownHuntPerks, crownHuntLiveShareScoring
    case reportTicketsBrowser, chatReplies, eventDetailsRequirePaid
    case partnerMemberOffersRequirePaid, crownHuntRequirePaid

    var contractDefault: Bool {
        switch self {
        case .crownHuntSpawn, .partnerInsightsPassBy, .crownHuntPerks,
             .crownHuntLiveShareScoring, .reportTicketsBrowser, .chatReplies,
             .eventDetailsRequirePaid, .partnerMemberOffersRequirePaid,
             .crownHuntRequirePaid:
            false
        default:
            true
        }
    }
}

struct FeatureFlags: Equatable, Sendable {
    private let values: [FeatureFlag: Bool]

    static let contractDefaults = FeatureFlags(values: Dictionary(
        uniqueKeysWithValues: FeatureFlag.allCases.map { ($0, $0.contractDefault) }
    ))

    static func resolve(from map: [String: Any]?) -> FeatureFlags {
        FeatureFlags(values: Dictionary(uniqueKeysWithValues: FeatureFlag.allCases.map {
            ($0, map?[$0.rawValue] as? Bool ?? $0.contractDefault)
        }))
    }

    func isEnabled(_ flag: FeatureFlag) -> Bool {
        values[flag] ?? flag.contractDefault
    }
}

enum FeatureGate {
    static func isAvailable(
        flags: FeatureFlags,
        flag: FeatureFlag,
        memberGated: Bool,
        access: AccountAccess
    ) -> Bool {
        flags.isEnabled(flag) && !access.isRestricted
            && (!memberGated || MemberGating.allows(access: access))
    }
}
