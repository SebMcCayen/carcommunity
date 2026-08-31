import Foundation

/// Kronjakt (Crown Hunt) domain model + pure logic — the iOS port of the
/// crownhunt read surface (Android `crownhunt/CrownHunt.kt`, `PerkShop.kt`,
/// `CrownHuntBoard.kt`, `CrownSeasonClock.kt`), restricted to the
/// MAP-INDEPENDENT core: crown points + personal stats, claim history, season
/// standings and the perk shop. Spawns / traps / live map markers are OUT of
/// scope (a later Mapbox-dependent slice).
///
/// Mirrors the language-neutral contract (contracts/schemas/crown-hunt.schema.json)
/// — the point/claim-result vocabularies, the rarity tiers, the leaderboard
/// counter shape and the perk families. Pure Swift so every enum mapping and
/// tolerant decode is unit-testable and shared by the repositories and screens.

// MARK: - Vocabularies

/// Point lifecycle status (`crownHuntPoints/{id}.status`) — the contract
/// `crownHuntPointStatus`. Only ``active`` points are member-visible.
enum CrownHuntPointStatus: String, Equatable, Sendable, CaseIterable {
    case draft
    case active
    case paused
    case ended

    /// The Firestore wire value (identical to the case name; kept as an
    /// explicit accessor so call sites read like Android's `status.wire`).
    var wire: String { rawValue }

    static func fromWire(_ value: String?) -> CrownHuntPointStatus? {
        guard let value else { return nil }
        return CrownHuntPointStatus(rawValue: value)
    }
}

/// A hand-placed point claim result (`crownHunt.submitClaim`) — the contract
/// `crownHuntClaimResult`, all 11 codes. Eligibility failures are RESULT CODES
/// with localized messages, not errors (legacy parity). Carried by a claim
/// history row so the screen can render the localized outcome.
enum CrownHuntClaimResult: String, Equatable, Sendable, CaseIterable {
    case awarded
    case alreadyClaimed = "already_claimed"
    case outsideGeofence = "outside_geofence"
    case movingTooFast = "moving_too_fast"
    case positionTooOld = "position_too_old"
    case pointInactive = "point_inactive"
    case cooldownActive = "cooldown_active"
    case dailyLimitReached = "daily_limit_reached"
    case riskReview = "risk_review"
    case featureDisabled = "feature_disabled"
    case notEligible = "not_eligible"

    /// The Firestore wire value (differs from the case name for the
    /// snake_case codes, so the raw value IS the wire spelling).
    var wire: String { rawValue }

    static func fromWire(_ value: String?) -> CrownHuntClaimResult? {
        guard let value else { return nil }
        return CrownHuntClaimResult(rawValue: value)
    }
}

/// A crown's rarity tier — the contract `crownHuntRarity`, ascending. Only
/// auto-spawned crowns are tiered (hand-placed points have no rarity); the
/// stats surface uses it for the per-rarity breakdown and the "rarest crown".
enum CrownRarity: String, Equatable, Sendable, CaseIterable {
    case common
    case uncommon
    case rare
    case legendary

    var wire: String { rawValue }

    static func fromWire(_ value: String?) -> CrownRarity? {
        guard let value else { return nil }
        return CrownRarity(rawValue: value)
    }
}

/// A perk family, mirrored from the server catalog's `kind` — the iOS port of
/// Android's `PerkKind`. Drives only the display label in this slice (the
/// activation path each kind takes is a later PR). An unrecognised wire value
/// maps to nil so a drifted catalog entry is dropped rather than mislabelled.
enum PerkKind: String, Equatable, Sendable, CaseIterable {
    case trap
    case shield
    case boost

    var wire: String { rawValue }

    static func fromWire(_ value: String?) -> PerkKind? {
        guard let value else { return nil }
        return PerkKind(rawValue: value)
    }
}

// MARK: - Claim history

/// One row of the member's own claim history (`crownHuntClaims/{claimId}`,
/// owner-only read — firestore.rules) — the contract `crownHuntClaim`,
/// narrowed to what the history list renders. There is no Android counterpart:
/// the Android hub does not surface claim history, but the owner read is
/// rules-permitted and the schema is shared, so the iOS list mirrors the
/// contract shape directly.
///
/// Risk data is NOT here (it lives in the backend-only `crownHuntClaimRisk`),
/// so a history row can never leak an anti-fraud score.
struct CrownHuntClaim: Equatable, Sendable, Identifiable {
    /// The claim document id (SHA-256-scoped idempotency key).
    let id: String
    /// The point this attempt targeted. Kept even when the point was later
    /// deleted — historical claims are retained as an audit trail.
    let pointId: String
    let result: CrownHuntClaimResult
    /// When the attempt was made (`claimedAt`), or `createdAt` as a fallback.
    let claimedAt: Date?
    /// Points awarded on an `awarded` result; nil for every other outcome.
    let pointsAwarded: Int?
}

// MARK: - Leaderboard / stats value types

/// One member's raw leaderboard counter for a scope, before ranking + naming —
/// the iOS port of Android's `CrownLeaderboardCounter`, mirroring the contract
/// `crownHuntLeaderboardEntryDoc`.
struct CrownLeaderboardCounter: Equatable, Sendable {
    let uid: String
    let points: Int
    let crownsCollected: Int
}

/// One ranked, name-resolved row shown in the leaderboard — Android's
/// `CrownLeaderboardRow`.
struct CrownLeaderboardRow: Equatable, Sendable, Identifiable {
    let rank: Int
    let uid: String
    let displayName: String
    let points: Int
    let crownsCollected: Int
    /// True for the signed-in viewer's own row, so the UI can highlight it.
    let isViewer: Bool

    /// Stable list identity — a uid is unique within a scope's ranked page.
    var id: String { uid }
}

/// This season's ranked top scores, plus the viewer's own rank in the scope —
/// Android's `CrownSeasonBoard`.
struct CrownSeasonBoard: Equatable, Sendable {
    let seasonId: String
    let rows: [CrownLeaderboardRow]
    /// The viewer's rank in this scope, or nil when they have not collected
    /// this season (or their rank is outside the fetched page).
    let viewerRank: Int?

    /// An empty board for the current season — used as the loading/empty seed.
    static func empty(seasonId: String) -> CrownSeasonBoard {
        CrownSeasonBoard(seasonId: seasonId, rows: [], viewerRank: nil)
    }
}

/// The subset of a `crownHuntUserStats/{uid}` document the hub reads — Android's
/// `CrownUserStatsDoc`. Owner-only read (firestore.rules). Absent rarity buckets
/// read as 0; `rarest` is nil for a member who has only collected hand-placed
/// crowns.
struct CrownUserStatsDoc: Equatable, Sendable {
    let byRarity: [CrownRarity: Int]
    let streakCurrent: Int
    let streakBest: Int
    let seasonsWon: Int
    let rarest: CrownRarity?
}

/// The signed-in member's own Kronjakt statistics for the hub — Android's
/// `CrownPersonalStats`.
struct CrownPersonalStats: Equatable, Sendable {
    /// Lifetime Crown Points from the all-time board (both crown sources).
    let points: Int
    /// Lifetime crowns collected.
    let crownsCollected: Int
    /// Rank this season, or nil when the viewer is outside the fetched page.
    let seasonRank: Int?
    /// Crown Points earned this season.
    let seasonPoints: Int
    /// Crowns collected this season.
    let seasonCrowns: Int
    /// Auto-spawned crowns collected, by rarity.
    let byRarity: [CrownRarity: Int]
    /// Consecutive-day collection streak.
    let streakCurrent: Int
    /// Best streak ever.
    let streakBest: Int
    /// Lifetime season victories.
    let seasonsWon: Int
    /// The rarest auto-spawned crown ever collected, or nil if none yet.
    let rarest: CrownRarity?
}

// MARK: - Season clock

/// The current Kronjakt SEASON id, client side — the iOS port of Android's
/// `CrownSeasonClock`.
///
/// A season is one calendar MONTH in Europe/Stockholm and its id is `YYYY-MM`,
/// mirroring `seasonIdForInstant` in the backend. The client needs it only to
/// pick WHICH leaderboard scope to read for "this season's top score"; the
/// backend stays authoritative for every count.
enum CrownSeasonClock {
    /// The reserved scope id for the never-resetting all-time board.
    static let allTimeScope = "alltime"

    private static let stockholm = TimeZone(identifier: "Europe/Stockholm")!
    private static let posixLocale = Locale(identifier: "en_US_POSIX")

    /// The `YYYY-MM` season id `date` falls in, in `zone` (default Stockholm).
    ///
    /// Extracts the year/month via `Calendar` rather than allocating a
    /// `DateFormatter` on every call — this runs on every repository read —
    /// and formats the id with an `en_US_POSIX` locale so the digits are
    /// always ASCII: the backend's doc ids are a plain `YYYY-MM`, and a device
    /// whose locale uses a non-Latin numbering system would otherwise format
    /// a season id that does not exist and read an empty board.
    static func seasonId(for date: Date, zone: TimeZone = stockholm) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let components = calendar.dateComponents([.year, .month], from: date)
        return String(
            format: "%04d-%02d", locale: posixLocale, components.year ?? 0, components.month ?? 0
        )
    }

    /// The current `YYYY-MM` season id.
    static func currentSeasonId(now: Date = Date(), zone: TimeZone = stockholm) -> String {
        seasonId(for: now, zone: zone)
    }
}
