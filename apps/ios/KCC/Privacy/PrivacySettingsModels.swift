import CoreFoundation
import Foundation

/// Owner-controlled privacy choices. Optional Partner Insights requires explicit
/// versioned consent; historical booleans alone never grant it. Leaderboard
/// visibility retains its existing opt-out contract.
struct PrivacySettingsChoices: Equatable, Sendable {
    let partnerStatsOptIn: Bool
    let leaderboardShown: Bool

    static let partnerStatsConsentVersion = 1
    static let contractDefaults = PrivacySettingsChoices(
        partnerStatsOptIn: false,
        leaderboardShown: true
    )

    /// The Firebase adapter supplies a date only for a real Firestore Timestamp.
    /// Missing consent evidence means OFF; present malformed evidence locks the
    /// screen rather than replacing an existing choice with a saveable default.
    static func decode(
        _ data: [String: Any]?,
        consentTimestamp: Date? = nil
    ) -> PrivacySettingsChoices? {
        let data = data ?? [:]
        let partner: Bool
        if let value = data["anonymousPartnerStatsOptIn"] {
            guard let value = strictBoolean(value) else { return nil }
            partner = value
        } else {
            partner = false
        }

        var hasCurrentVersion = false
        if let value = data["anonymousPartnerStatsConsentVersion"] {
            guard let number = value as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue.isFinite,
                  number.doubleValue.rounded() == number.doubleValue
            else { return nil }
            hasCurrentVersion = number.doubleValue == Double(partnerStatsConsentVersion)
        }
        let hasTimestamp = data.keys.contains("anonymousPartnerStatsConsentAt")
        if hasTimestamp {
            guard let consentTimestamp, consentTimestamp.timeIntervalSince1970.isFinite,
                  consentTimestamp.timeIntervalSince1970 > 0 else { return nil }
        }

        let leaderboardOptOut: Bool
        if let value = data["leaderboardOptOut"] {
            guard let value = strictBoolean(value) else { return nil }
            leaderboardOptOut = value
        } else {
            leaderboardOptOut = false
        }
        return PrivacySettingsChoices(
            partnerStatsOptIn: partner && hasCurrentVersion && hasTimestamp,
            leaderboardShown: !leaderboardOptOut
        )
    }

    private static func strictBoolean(_ value: Any) -> Bool? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
}

/// One settled owner-document listener result. A missing document is a valid
/// default-valued snapshot; a read error or malformed privacy field is a
/// failure and never a default.
enum PrivacySettingsSnapshot: Equatable, Sendable {
    case unconfirmed
    case loaded(PrivacySettingsChoices)
    case failed(code: String?)

    /// Cache-only and locally pending snapshots are not authoritative privacy
    /// state. Returning `nil` keeps the coordinator locked until Firestore has
    /// confirmed the document with the server.
    static func authoritative(
        data: [String: Any]?,
        isFromCache: Bool,
        hasPendingWrites: Bool,
        consentTimestamp: Date? = nil
    ) -> PrivacySettingsSnapshot? {
        guard !isFromCache, !hasPendingWrites else { return nil }
        guard let choices = PrivacySettingsChoices.decode(data, consentTimestamp: consentTimestamp) else {
            return .failed(code: nil)
        }
        return .loaded(choices)
    }
}

/// PII-safe write failure. Firebase messages can contain project/document
/// paths, so only the stable status name crosses the repository boundary.
struct PrivacySettingsWriteError: Error, Equatable, Sendable {
    let code: String?
}

enum PrivacySettingsUiState: Equatable, Sendable {
    case loading
    case unavailable
    case loaded(PrivacySettingsChoices)
    case failed(code: String?)
}

enum PrivacySettingsSaveStatus: Equatable, Sendable {
    case idle
    case saving
    case saved
    case failed(code: String?)
}

/// Local pending values. They seed once from the first definitive read so a
/// later listener emission cannot overwrite an edit in progress.
struct PrivacySettingsDraft: Equatable, Sendable {
    var partnerStatsOptIn: Bool
    var leaderboardShown: Bool

    init(_ choices: PrivacySettingsChoices) {
        partnerStatsOptIn = choices.partnerStatsOptIn
        leaderboardShown = choices.leaderboardShown
    }
}
