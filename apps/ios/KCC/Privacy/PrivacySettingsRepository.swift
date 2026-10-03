import Foundation

/// Owner-scoped access to the two privacy preferences. Both writes are direct,
/// rules-validated updates to `userPrivate/{uid}`; no callable is involved.
protocol PrivacySettingsRepository: AnyObject, Sendable {
    /// Each call returns a fresh live stream. Ending iteration must detach its
    /// Firestore listener.
    func settings(uid: String) -> AsyncStream<PrivacySettingsSnapshot>

    func setPartnerStatsOptIn(uid: String, optIn: Bool) async throws
    func setLeaderboardOptOut(uid: String, optOut: Bool) async throws
}
