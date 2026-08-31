import Foundation

/// Localized badge-string key lookup — the iOS port of Android's
/// `badges/BadgeStrings.kt`.
///
/// Returns the GENERATED Localizable.xcstrings key (never a literal string):
/// the screen resolves it through `LocalizedStringKey` / `String(localized:)`,
/// exactly as the garage feature resolves `garage.*` keys. The catalog itself
/// is generated from contracts/localization/{sv,en}.json and is NEVER
/// hand-edited; every key here is asserted present at build time by the app's
/// string catalog. Pure Swift — no Firebase, no SwiftUI.
enum BadgeStrings {
    /// The localized name res key for a known badge key, or nil for an unknown
    /// key (the screen then falls back to the denormalized award name, which
    /// is always Swedish) — Android's `badgeNameRes`.
    ///
    /// The keys are a fixed 1:1 rename (`_` in the badge key stays, the key is
    /// prefixed with `badges.badgeNames.`), so a single computed key is exact
    /// for every catalog badge and needs no per-key switch. An unknown key
    /// returns nil so the fallback name is used.
    static func badgeNameKey(for badgeKey: String) -> String? {
        knownBadgeNameKeys.contains(badgeKey) ? "badges.badgeNames.\(badgeKey)" : nil
    }

    /// Ladder name (Kronjägare / Crown Hunter, …) — Android's `ladderNameRes`.
    static func ladderNameKey(_ id: BadgeLadderId) -> String {
        "badgeShowcase.ladderNames.\(id.rawValue)"
    }

    /// One-line "what this ladder measures" caption — Android's
    /// `ladderTaglineRes`.
    static func ladderTaglineKey(_ id: BadgeLadderId) -> String {
        "badgeShowcase.ladderTaglines.\(id.rawValue)"
    }

    /// Requirement sentence with a single threshold placeholder — Android's
    /// `ladderRequirementRes`.
    static func ladderRequirementKey(_ id: BadgeLadderId) -> String {
        "badgeShowcase.ladderRequirements.\(id.rawValue)"
    }

    /// Tier name (Brons / Bronze, …) — Android's `tierNameRes`.
    static func tierNameKey(_ tier: BadgeTier) -> String {
        "badgeShowcase.tierNames.\(tier.rawValue)"
    }

    /// Every badge key that carries a generated localized name — the milestone
    /// keys plus the 31 tiered ladder rungs. Mirrors the `when` in Android's
    /// `badgeNameRes`; an unknown key falls back to the award's Swedish name.
    static let knownBadgeNameKeys: Set<String> =
        Set(badgeMilestoneKeys).union(badgeLadders.flatMap(\.badgeKeys))
}
