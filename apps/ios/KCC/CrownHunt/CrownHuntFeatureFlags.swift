import Foundation

/// The Crown-Hunt feature flags this surface honors — keys and contract
/// defaults mirror contracts/features/feature-flags.json exactly, the canonical
/// registry (Android's `config/FeatureFlags.kt`).
///
/// The backend (`config/featureFlags`) is authoritative; clients read it on
/// launch and fall back to these defaults when the document, or an individual
/// field, is absent or malformed — a flag degrades to its documented default,
/// never to "off". Pure Swift so the merge/lookup is unit-testable.
enum CrownHuntFeatureFlag: String, CaseIterable, Sendable {
    /// Kronjakt as a whole (hand-placed points). Contract default ON.
    case crownHunt = "crownHunt"
    /// The AUTOMATIC spawn half. Contract default OFF — out of scope here, but
    /// registered so the merge covers every crownHunt* key.
    case crownHuntSpawn = "crownHuntSpawn"
    /// The Kronjakt SHOP (perks). Contract default OFF — while off the shop tab
    /// is never rendered and the buy callable rejects.
    case crownHuntPerks = "crownHuntPerks"
    /// The live-share SCORING rule. Contract default OFF — passed to the
    /// instructions surface so its live-share section shows only while on.
    case crownHuntLiveShareScoring = "crownHuntLiveShareScoring"

    /// The contract default (feature-flags.json). Load-bearing for the OFF
    /// ones: the shop / spawn / live-share features stay dark until an operator
    /// deliberately switches them on.
    var contractDefault: Bool {
        switch self {
        case .crownHunt: return true
        case .crownHuntSpawn: return false
        case .crownHuntPerks: return false
        case .crownHuntLiveShareScoring: return false
        }
    }
}

/// A resolved snapshot of the Crown-Hunt flags — every flag folded against its
/// contract default. Pure value type so gating decisions (does the shop tab
/// render? is the live-share section shown?) are testable without Firebase.
struct CrownHuntFlags: Equatable, Sendable {
    /// The perk shop is available end-to-end. Mirrors Android's `perksEnabled`
    /// gate on `CrownHuntRoute`.
    let perksEnabled: Bool
    /// The live-share scoring rule is in force (the instructions surface reads
    /// this only to DESCRIBE the rule; out of this slice's screens otherwise).
    let liveShareScoringEnabled: Bool

    /// Every flag at its contract default — the config-less / pre-read posture.
    static let contractDefaults = CrownHuntFlags(
        perksEnabled: CrownHuntFeatureFlag.crownHuntPerks.contractDefault,
        liveShareScoringEnabled: CrownHuntFeatureFlag.crownHuntLiveShareScoring.contractDefault
    )

    /// Folds a raw `config/featureFlags` document map onto the flags, taking a
    /// present boolean as-is and every absent/malformed field at its contract
    /// default — the same "degrade to default, never to off" rule as Android's
    /// `FeatureFlags.merge`.
    static func resolve(from document: [String: Any]?) -> CrownHuntFlags {
        func value(_ flag: CrownHuntFeatureFlag) -> Bool {
            (document?[flag.rawValue] as? Bool) ?? flag.contractDefault
        }
        return CrownHuntFlags(
            perksEnabled: value(.crownHuntPerks),
            liveShareScoringEnabled: value(.crownHuntLiveShareScoring)
        )
    }
}

/// Reads the Crown-Hunt feature flags — Firebase-free protocol so the shop
/// gating is unit-testable with a fake. The one implementation
/// (``FirebaseCrownHuntFeatureFlagsRepository``) is a rules-gated one-shot read
/// of `config/featureFlags` (readable by any authenticated user —
/// firestore.rules), folded onto the contract defaults.
protocol CrownHuntFeatureFlagsRepository: AnyObject, Sendable {
    /// The resolved flags, or the contract defaults on any read failure.
    func flags() async -> CrownHuntFlags
}
