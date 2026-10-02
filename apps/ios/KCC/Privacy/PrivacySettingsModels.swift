import Foundation

/// The two owner-controlled privacy choices stored on `userPrivate/{uid}`.
/// Partner statistics requires explicit opt-in; an absent field is disabled.
/// Leaderboard visibility remains opt-out, so an absent field is shown.
struct PrivacySettingsChoices: Equatable, Sendable {
    let partnerStatsOptIn: Bool
    let leaderboardShown: Bool

    static let contractDefaults = PrivacySettingsChoices(
        partnerStatsOptIn: false,
        leaderboardShown: true
    )

    /// Decodes a successful Firestore snapshot. Missing fields use the shared
    /// defaults, while a PRESENT malformed field fails closed: the caller must
    /// not turn invalid server data into a saveable default that could replace
    /// an existing privacy choice.
    static func decode(_ data: [String: Any]?) -> PrivacySettingsChoices? {
        let data = data ?? [:]
        let partner: Bool
        if data.keys.contains("anonymousPartnerStatsOptIn") {
            guard let value = data["anonymousPartnerStatsOptIn"] as? Bool else { return nil }
            partner = value
        } else {
            partner = false
        }

        let leaderboardOptOut: Bool
        if data.keys.contains("leaderboardOptOut") {
            guard let value = data["leaderboardOptOut"] as? Bool else { return nil }
            leaderboardOptOut = value
        } else {
            leaderboardOptOut = false
        }
        return PrivacySettingsChoices(
            partnerStatsOptIn: partner,
            leaderboardShown: !leaderboardOptOut
        )
    }
}

/// One settled owner-document listener result. A missing document is a valid
/// default-valued snapshot; a read error or malformed privacy field is a
/// failure and never a default.
enum PrivacySettingsSnapshot: Equatable, Sendable {
    case loaded(PrivacySettingsChoices)
    case failed(code: String?)
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
