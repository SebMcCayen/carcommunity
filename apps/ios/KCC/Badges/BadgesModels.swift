import Foundation

/// Badges domain model + pure catalog/folding logic — the iOS port of
/// Android's `badges/Badge.kt`, `badges/BadgeLadders.kt` and
/// `badges/BadgeShowcase.kt`, restricted to the OWN badge-wall slice.
///
/// Read-only: awards live at `users/{uid}/badges/{badgeKey}` (owner read,
/// backend-only writes — firebase/firestore.rules), and the authoritative
/// per-ladder counters live at the backend-only `badgeProgress/{uid}`
/// document that `firebase/firestore.rules` denies to EVERY client, owner
/// included. The wall therefore folds two inputs — the award documents and
/// the counters handed to the OWN client by the owner-only
/// `badges-getMyProgress` callable — into the full catalog.
///
/// Pure Swift (no Firebase, no SwiftUI) so it is fully unit-testable and
/// shared by the repository, coordinator, and screen.

// MARK: - Award document

/// One `users/{uid}/badges/{badgeKey}` award, as the wall reads it. The
/// document denormalizes name/awardedAt so a member's held badges render
/// without a catalog lookup; the screen prefers the localized name for a
/// known key and falls back to this Swedish `fallbackName` otherwise.
struct Badge: Equatable, Sendable {
    /// The badge key (document id). Frozen for the five original milestones.
    let key: String
    /// Denormalized catalog name (Swedish) from the award document, or nil.
    let fallbackName: String?
    /// When the badge was awarded, or nil when the document carries no date.
    let awardedAt: Date?

    /// Tolerant decoding of a badge document's fields — the iOS port of
    /// Android's `DocumentSnapshot.toBadge()`: the key falls back to the
    /// document id when the denormalized `badgeKey` is absent, and every
    /// other field degrades to nil rather than dropping the badge.
    static func fromMap(id: String, map: [String: Any]) -> Badge {
        Badge(
            key: (map["badgeKey"] as? String) ?? id,
            fallbackName: map["name"] as? String,
            awardedAt: awardedAtDate(from: map["awardedAt"])
        )
    }

    /// Reads a Firestore timestamp-shaped value into a `Date` without a
    /// Firebase dependency in this pure type: the Firebase mapping layer
    /// passes a `Date` (its `Timestamp.dateValue()`), and tests pass a
    /// `Date` directly.
    private static func awardedAtDate(from value: Any?) -> Date? {
        value as? Date
    }
}

/// Pure badge-list ordering — Android's `Badges.sortedForList`.
enum Badges {
    /// Newest award first; an undated award sorts last.
    static func sortedForList(_ badges: [Badge]) -> [Badge] {
        badges.sorted { lhs, rhs in
            let left = lhs.awardedAt ?? Date.distantPast
            let right = rhs.awardedAt ?? Date.distantPast
            if left != right { return left > right }
            // Deterministic tie-break on the (frozen) key.
            return lhs.key < rhs.key
        }
    }
}

// MARK: - Ladder catalog (client mirror)

/// A ladder rung's rank. Declaration order is the ladder order (low → high) —
/// Android's `BadgeTier` ordinal.
enum BadgeTier: String, Equatable, Sendable, CaseIterable {
    case brons
    case silver
    case guld
    case platina

    static func fromKey(_ key: String?) -> BadgeTier? {
        guard let key else { return nil }
        return BadgeTier(rawValue: key)
    }

    /// Low → high rank, so a "highest held rung" comparison is total.
    var rank: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

/// How a ladder's threshold and counter are rendered — Android's
/// `BadgeLadderUnit`.
enum BadgeLadderUnit: Equatable, Sendable {
    /// A plain count (crowns, meets, days, convoys, vehicles, seasons, waves).
    case count
    /// Metres, shown as whole kilometres — the Vägfarare unit.
    case distanceMeters
}

/// Stable identity of a ladder. `key` matches the backend `ladder` field —
/// Android's `BadgeLadderId`.
enum BadgeLadderId: String, Equatable, Sendable, CaseIterable {
    case kronjagare
    case vagfarare
    case traffrav
    case trogen
    case konvojledare
    case samlare
    case sasongsmastare
    case vinkare

    static func fromKey(_ key: String?) -> BadgeLadderId? {
        guard let key else { return nil }
        return BadgeLadderId(rawValue: key)
    }

    /// Catalog display order, for stable sorting.
    var order: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

/// One rung of a ladder: the badge key it awards and the counter it needs —
/// Android's `BadgeRung`.
struct BadgeRung: Equatable, Sendable {
    let tier: BadgeTier
    let badgeKey: String
    /// Inclusive threshold on the ladder's metric (>= qualifies).
    let threshold: Int64
}

/// One tiered ladder — Android's `BadgeLadder`.
struct BadgeLadder: Equatable, Sendable {
    let id: BadgeLadderId
    let unit: BadgeLadderUnit
    /// Bottom-to-top. Trogen has three rungs; the rest have four.
    let rungs: [BadgeRung]

    var badgeKeys: [String] { rungs.map(\.badgeKey) }

    func rung(for tier: BadgeTier) -> BadgeRung? {
        rungs.first { $0.tier == tier }
    }
}

private let metresPerKm: Int64 = 1_000

/// The eight ladders, in the catalog's display order — a verbatim mirror of
/// Android's `BADGE_LADDERS` (functions/src/badges/badge-core.ts). DISPLAY
/// ONLY: qualification is a pure `>=` test the backend runs against the
/// backend-only `badgeProgress/{uid}` counters; a drift here can only mis-draw
/// a goal line, never award a badge.
let badgeLadders: [BadgeLadder] = [
    BadgeLadder(
        id: .kronjagare,
        unit: .count,
        rungs: [
            BadgeRung(tier: .brons, badgeKey: "kronjagare_brons", threshold: 10),
            BadgeRung(tier: .silver, badgeKey: "kronjagare_silver", threshold: 50),
            BadgeRung(tier: .guld, badgeKey: "kronjagare_guld", threshold: 250),
            BadgeRung(tier: .platina, badgeKey: "kronjagare_platina", threshold: 1_000),
        ]
    ),
    BadgeLadder(
        id: .vagfarare,
        unit: .distanceMeters,
        rungs: [
            BadgeRung(tier: .brons, badgeKey: "vagfarare_brons", threshold: 100 * metresPerKm),
            BadgeRung(tier: .silver, badgeKey: "vagfarare_silver", threshold: 500 * metresPerKm),
            BadgeRung(tier: .guld, badgeKey: "vagfarare_guld", threshold: 2_000 * metresPerKm),
            BadgeRung(tier: .platina, badgeKey: "vagfarare_platina", threshold: 10_000 * metresPerKm),
        ]
    ),
    BadgeLadder(
        id: .traffrav,
        unit: .count,
        rungs: [
            // Brons/Silver deliberately mirror the existing first_event (1) and
            // five_events (5) badges — docs/gamification-system.md §7.2.
            BadgeRung(tier: .brons, badgeKey: "traffrav_brons", threshold: 1),
            BadgeRung(tier: .silver, badgeKey: "traffrav_silver", threshold: 5),
            BadgeRung(tier: .guld, badgeKey: "traffrav_guld", threshold: 25),
            BadgeRung(tier: .platina, badgeKey: "traffrav_platina", threshold: 100),
        ]
    ),
    // Three rungs only, by product decision (Q6): a 365-day Platina streak is
    // the loss-aversion hook the design doc rules out, so `trogen_platina` is
    // not a badge key at all. Guld at 100 is the top.
    BadgeLadder(
        id: .trogen,
        unit: .count,
        rungs: [
            BadgeRung(tier: .brons, badgeKey: "trogen_brons", threshold: 7),
            BadgeRung(tier: .silver, badgeKey: "trogen_silver", threshold: 30),
            BadgeRung(tier: .guld, badgeKey: "trogen_guld", threshold: 100),
        ]
    ),
    BadgeLadder(
        id: .konvojledare,
        unit: .count,
        rungs: [
            BadgeRung(tier: .brons, badgeKey: "konvojledare_brons", threshold: 1),
            BadgeRung(tier: .silver, badgeKey: "konvojledare_silver", threshold: 5),
            BadgeRung(tier: .guld, badgeKey: "konvojledare_guld", threshold: 25),
            BadgeRung(tier: .platina, badgeKey: "konvojledare_platina", threshold: 100),
        ]
    ),
    // Four rungs 1/3/6/10: the garage cap rose 5 → 10 (2026-08), so Platina at
    // the cap is now reachable.
    BadgeLadder(
        id: .samlare,
        unit: .count,
        rungs: [
            BadgeRung(tier: .brons, badgeKey: "samlare_brons", threshold: 1),
            BadgeRung(tier: .silver, badgeKey: "samlare_silver", threshold: 3),
            BadgeRung(tier: .guld, badgeKey: "samlare_guld", threshold: 6),
            BadgeRung(tier: .platina, badgeKey: "samlare_platina", threshold: 10),
        ]
    ),
    // Säsongsmästare — the scaling lifetime-championship ladder (metric
    // seasonsWon). Distinct from the per-season podium badges below.
    BadgeLadder(
        id: .sasongsmastare,
        unit: .count,
        rungs: [
            BadgeRung(tier: .brons, badgeKey: "sasongsmastare_brons", threshold: 1),
            BadgeRung(tier: .silver, badgeKey: "sasongsmastare_silver", threshold: 3),
            BadgeRung(tier: .guld, badgeKey: "sasongsmastare_guld", threshold: 5),
            BadgeRung(tier: .platina, badgeKey: "sasongsmastare_platina", threshold: 10),
        ]
    ),
    // Vinkare — the waves-sent ladder (metric wavesSent). A social greeting
    // counter, never speed or competition.
    BadgeLadder(
        id: .vinkare,
        unit: .count,
        rungs: [
            BadgeRung(tier: .brons, badgeKey: "vinkare_brons", threshold: 25),
            BadgeRung(tier: .silver, badgeKey: "vinkare_silver", threshold: 100),
            BadgeRung(tier: .guld, badgeKey: "vinkare_guld", threshold: 500),
            BadgeRung(tier: .platina, badgeKey: "vinkare_platina", threshold: 2_000),
        ]
    ),
]

/// The standalone (non-tiered) badges, in catalog order — Android's
/// `BADGE_MILESTONE_KEYS`: the five original milestones, the exclusive
/// early_tester ("Grundare") reward, then the three season PODIUM badges
/// (a single season's top three, by rank — not a ladder).
let badgeMilestoneKeys: [String] = [
    "first_event",
    "five_events",
    "helpful_member",
    "early_member",
    "garage_created",
    // Manually granted to a hand-picked UID list by an admin; never earned.
    "early_tester",
    "sasong_guld",
    "sasong_silver",
    "sasong_brons",
]

/// Kronpoäng credited once, the first time a rung is reached — Android's
/// `BADGE_TIER_POINTS` (TIER_POINTS_REWARD).
let badgeTierPoints: [BadgeTier: Int] = [
    .brons: 25,
    .silver: 75,
    .guld: 200,
    .platina: 500,
]

/// Every badge key in the catalog — the denominator of "x of y unlocked".
let badgeTotalCount: Int = badgeMilestoneKeys.count + badgeLadders.reduce(0) { $0 + $1.rungs.count }

private let ladderByBadgeKey: [String: (ladder: BadgeLadder, rung: BadgeRung)] = {
    var map: [String: (BadgeLadder, BadgeRung)] = [:]
    for ladder in badgeLadders {
        for rung in ladder.rungs {
            map[rung.badgeKey] = (ladder, rung)
        }
    }
    return map
}()

/// The ladder + rung a badge key belongs to, or nil for a standalone key —
/// Android's `rungForBadgeKey`.
func rungForBadgeKey(_ badgeKey: String) -> (ladder: BadgeLadder, rung: BadgeRung)? {
    ladderByBadgeKey[badgeKey]
}

/// Renders a threshold or counter for display, in the ladder's own unit —
/// Android's `formatLadderValue`. Distances are stored in metres and shown as
/// WHOLE kilometres, locale-independently (no grouping separators to disagree
/// with the Swedish copy), so "34 km / 100 km" reads as one quantity.
func formatLadderValue(unit: BadgeLadderUnit, value: Int64) -> String {
    switch unit {
    case .count:
        return String(value)
    case .distanceMeters:
        let km = (Double(value) / Double(metresPerKm)).rounded()
        return "\(Int64(km)) km"
    }
}

// MARK: - Progress counters

/// The signed-in member's OWN server-verified ladder counters, one per ladder
/// — Android's `BadgeCounters`.
///
/// Sourced from the owner-only `badges-getMyProgress` callable, which projects
/// the backend-only `badgeProgress/{uid}` document (denied to every client)
/// into these eight numbers for the caller's own uid. A field is nil only when
/// the value is not yet known — the callable has not resolved, failed, or is
/// absent in a config-less build — in which case that ladder shows no bar; it
/// is never a fabricated or estimated number.
struct BadgeCounters: Equatable, Sendable {
    var crownsCollected: Int64?
    var lifetimeDistanceMeters: Int64?
    var verifiedEventsAttended: Int64?
    var bestDayStreak: Int64?
    var convoysLed: Int64?
    var vehiclesInGarage: Int64?
    var seasonsWon: Int64?
    var wavesSent: Int64?

    init(
        crownsCollected: Int64? = nil,
        lifetimeDistanceMeters: Int64? = nil,
        verifiedEventsAttended: Int64? = nil,
        bestDayStreak: Int64? = nil,
        convoysLed: Int64? = nil,
        vehiclesInGarage: Int64? = nil,
        seasonsWon: Int64? = nil,
        wavesSent: Int64? = nil
    ) {
        self.crownsCollected = crownsCollected
        self.lifetimeDistanceMeters = lifetimeDistanceMeters
        self.verifiedEventsAttended = verifiedEventsAttended
        self.bestDayStreak = bestDayStreak
        self.convoysLed = convoysLed
        self.vehiclesInGarage = vehiclesInGarage
        self.seasonsWon = seasonsWon
        self.wavesSent = wavesSent
    }

    /// No counters known — a bar-less-but-complete wall.
    static let none = BadgeCounters()

    /// The observable value for `ladder`, or nil when the client does not yet
    /// hold that counter. The counters arrive already sanitised (finite,
    /// non-negative, floored) from the callable; a stray negative is still
    /// floored out here as defence.
    func observedValue(for ladder: BadgeLadderId) -> Int64? {
        let value: Int64?
        switch ladder {
        case .kronjagare: value = crownsCollected
        case .vagfarare: value = lifetimeDistanceMeters
        case .traffrav: value = verifiedEventsAttended
        case .trogen: value = bestDayStreak
        case .konvojledare: value = convoysLed
        case .samlare: value = vehiclesInGarage
        case .sasongsmastare: value = seasonsWon
        case .vinkare: value = wavesSent
        }
        guard let value, value >= 0 else { return nil }
        return value
    }
}

// MARK: - Folded wall

/// One ladder as the wall renders it — Android's `LadderProgress`.
struct LadderProgress: Equatable, Sendable, Identifiable {
    let ladder: BadgeLadder
    /// Every rung held, low → high. Ladders are monotonic; a tier is never
    /// revoked.
    let earnedRungs: [BadgeRung]
    /// The next rung to reach, or nil when the ladder is fully climbed.
    let nextRung: BadgeRung?
    /// Client-observable counter, or nil when only the server knows it.
    let observedValue: Int64?

    var id: BadgeLadderId { ladder.id }

    /// Highest rung held, or nil when the ladder has not been started.
    var highestRung: BadgeRung? { earnedRungs.last }

    /// The rung the medallion depicts: the highest held, else the locked
    /// first rung.
    var displayRung: BadgeRung { highestRung ?? ladder.rungs[0] }

    /// True when nothing is earned on this ladder — render it greyed.
    var isLocked: Bool { earnedRungs.isEmpty }

    /// True when every rung is held.
    var isComplete: Bool { nextRung == nil }

    /// How far along the climb to `nextRung`, in 0...1 — nil when there is no
    /// next rung or no observable counter, i.e. when no bar may be drawn.
    /// Measured from ZERO (not the previous rung) so the label ("34 / 50") and
    /// the fill describe the same quantity.
    var fractionToNext: Double? {
        guard let target = nextRung?.threshold, let value = observedValue else { return nil }
        if target <= 0 { return 1 }
        return min(max(Double(value) / Double(target), 0), 1)
    }
}

/// A held standalone badge (the non-tiered milestones) — Android's
/// `MilestoneBadge`.
struct MilestoneBadge: Equatable, Sendable, Identifiable {
    let key: String
    let fallbackName: String?
    let awardedAt: Date?

    var id: String { key }
}

/// One earned award as the recency summary renders it — Android's
/// `EarnedAward`: a single held ladder tier OR a standalone milestone, each
/// its own item.
struct EarnedAward: Equatable, Sendable, Identifiable {
    let badgeKey: String
    /// The ladder this tier belongs to, or nil for a standalone milestone.
    let ladderId: BadgeLadderId?
    /// The tier's rank, or nil for a milestone (which has none).
    let tier: BadgeTier?
    /// The award doc's denormalized name — the fallback for a milestone with
    /// no catalog string.
    let fallbackName: String?
    /// When it was acquired; null-dated awards sort last, never first.
    let awardedAt: Date?

    var id: String { badgeKey }

    /// True for a standalone milestone, false for a ladder tier.
    var isMilestone: Bool { ladderId == nil }
}

/// The whole own-profile badge wall, folded from award documents and the
/// owner's counters — Android's `BadgeShowcase`.
struct BadgeShowcase: Equatable, Sendable {
    /// All eight ladders, always — an unstarted ladder renders locked, not
    /// hidden.
    let ladders: [LadderProgress]
    /// Standalone milestones the member holds; empty until one is awarded.
    let milestones: [MilestoneBadge]
    /// The member's earned awards, newest-acquired first, capped at
    /// ``recentAwardsLimit`` — the source of the recency summary strip.
    let recentAwards: [EarnedAward]
    /// Distinct catalog badges held (unknown keys excluded).
    let earnedCount: Int
    /// Every badge in the catalog — the denominator of "x of y unlocked".
    let totalCount: Int
    /// Award timestamps by badge key, for unlock-date captions and detail.
    let awardedAtByKey: [String: Date]

    /// False → the wall shows the inviting "here is what you can earn" copy.
    var hasAnyBadge: Bool { earnedCount > 0 }

    /// Ladders with a rung still to climb, closest-to-done first — the hook.
    var laddersInProgress: [LadderProgress] {
        ladders
            .filter { !$0.isComplete }
            .sorted { lhs, rhs in
                // Ladders with a real bar lead, most-complete first; the rest
                // keep catalog order behind them so the list is stable.
                let lf = lhs.fractionToNext ?? -1
                let rf = rhs.fractionToNext ?? -1
                if lf != rf { return lf > rf }
                return lhs.ladder.id.order < rhs.ladder.id.order
            }
    }

    /// The recency summary shows at most this many awards, newest first.
    static let recentAwardsLimit = 6

    /// Folds the owner's award documents (and optional counters) into the
    /// wall — Android's `BadgeShowcase.from`.
    ///
    /// Robust to anything Firestore can hand back: unknown/retired badge keys
    /// are ignored, duplicates collapse, and a ladder holding a HIGH rung but
    /// missing a lower one (which the monotonic backend never produces) still
    /// reports the highest held rung and the next unheld one above it.
    static func from(badges: [Badge], counters: BadgeCounters = .none) -> BadgeShowcase {
        let heldKeys = Set(badges.map(\.key))

        // Newest timestamp per key: were the same key ever to arrive on more
        // than one doc, the newest award wins — matching how the recency strip
        // collapses duplicates below.
        var awardedAt: [String: Date] = [:]
        for badge in badges {
            guard let date = badge.awardedAt else { continue }
            if let existing = awardedAt[badge.key] {
                awardedAt[badge.key] = max(existing, date)
            } else {
                awardedAt[badge.key] = date
            }
        }

        let ladders = badgeLadders.map { ladder -> LadderProgress in
            let earned = ladder.rungs.filter { heldKeys.contains($0.badgeKey) }
            // The next rung is the lowest UNHELD one, so a gap left by a
            // partial write is offered again rather than skipped.
            let next = ladder.rungs.first { !heldKeys.contains($0.badgeKey) }
            return LadderProgress(
                ladder: ladder,
                earnedRungs: earned,
                nextRung: next,
                observedValue: counters.observedValue(for: ladder.id)
            )
        }

        let milestones = badgeMilestoneKeys.compactMap { key -> MilestoneBadge? in
            guard let badge = badges.first(where: { $0.key == key }) else { return nil }
            return MilestoneBadge(key: key, fallbackName: badge.fallbackName, awardedAt: badge.awardedAt)
        }

        let milestoneKeys = Set(badgeMilestoneKeys)
        let catalogKeys = milestoneKeys.union(badgeLadders.flatMap(\.badgeKeys))

        // The recency strip source: one flat entry per HELD catalog badge —
        // every ladder tier and every milestone — newest acquired first.
        // Unknown/retired keys dropped, duplicate docs collapsed.
        let recentAwards =
            badges
            .compactMap { badge -> EarnedAward? in
                if let match = rungForBadgeKey(badge.key) {
                    return EarnedAward(
                        badgeKey: badge.key,
                        ladderId: match.ladder.id,
                        tier: match.rung.tier,
                        fallbackName: nil,
                        awardedAt: badge.awardedAt
                    )
                } else if milestoneKeys.contains(badge.key) {
                    return EarnedAward(
                        badgeKey: badge.key,
                        ladderId: nil,
                        tier: nil,
                        fallbackName: badge.fallbackName,
                        awardedAt: badge.awardedAt
                    )
                }
                return nil
            }
            // Newest first; an undated award sorts last, ties break on the
            // (frozen) key so the order is fully deterministic.
            .sorted { lhs, rhs in
                let left = lhs.awardedAt ?? Date.distantPast
                let right = rhs.awardedAt ?? Date.distantPast
                if left != right { return left > right }
                return lhs.badgeKey < rhs.badgeKey
            }

        // Collapse duplicate docs for the same key AFTER sorting, so the
        // survivor is the NEWEST one — keeping recency consistent with
        // `awardedAtByKey`, which likewise carries the newest per key.
        var seen = Set<String>()
        let dedupedAwards =
            recentAwards
            .filter { seen.insert($0.badgeKey).inserted }
            .prefix(recentAwardsLimit)

        return BadgeShowcase(
            ladders: ladders,
            milestones: milestones,
            recentAwards: Array(dedupedAwards),
            earnedCount: heldKeys.filter { catalogKeys.contains($0) }.count,
            totalCount: badgeTotalCount,
            awardedAtByKey: awardedAt
        )
    }
}
