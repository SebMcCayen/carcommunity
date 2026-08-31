import Foundation

/// The pure fold that turns the raw `crownHuntLeaderboardEntries` /
/// `crownHuntUserStats` reads into the member-facing stats + season board —
/// the iOS port of Android's `CrownHuntBoard` object.
///
/// The RANKING here MUST agree with the server's `rankLeaderboard` (and the
/// admin dashboard's `rankLeaderboardCounters`), or the "you are #3" a member
/// reads would disagree with the authoritative board — so it is pinned by unit
/// tests, exactly like the Android object it mirrors. Pure Swift: no Firebase,
/// so the ranking and the composition are testable off-device.
enum CrownHuntBoard {
    /// How many top rows the hub leaderboard shows — Android's `LEADERBOARD_TOP_N`.
    static let leaderboardTopN = 10

    /// Ranks `counters` into leaderboard order — the "strictly better" ordering
    /// the backend's `rankLeaderboard` uses: points DESC, then crownsCollected
    /// DESC, then uid ASC as the final, deterministic tiebreak. Rank is 1-based.
    static func rank(_ counters: [CrownLeaderboardCounter]) -> [(counter: CrownLeaderboardCounter, rank: Int)] {
        let sorted = counters.sorted { lhs, rhs in
            if lhs.points != rhs.points { return lhs.points > rhs.points }
            if lhs.crownsCollected != rhs.crownsCollected {
                return lhs.crownsCollected > rhs.crownsCollected
            }
            return lhs.uid < rhs.uid
        }
        return sorted.enumerated().map { (counter: $1, rank: $0 + 1) }
    }

    /// Builds the season board from raw counters, resolving each uid's display
    /// name from `names` (falling back to a short uid stub when a profile is
    /// missing — a private/deleted profile never blanks a row).
    ///
    /// `viewerUid` is the signed-in member, so their row is flagged and their
    /// rank surfaced. ``CrownSeasonBoard/viewerRank`` is their position IN THE
    /// RANKED PAGE — nil when they are not in the fetched top rows.
    static func board(
        counters: [CrownLeaderboardCounter],
        viewerUid: String?,
        names: [String: String],
        seasonId: String
    ) -> CrownSeasonBoard {
        let rows = rank(counters).map { entry in
            CrownLeaderboardRow(
                rank: entry.rank,
                uid: entry.counter.uid,
                displayName: resolveName(entry.counter.uid, names),
                points: entry.counter.points,
                crownsCollected: entry.counter.crownsCollected,
                isViewer: entry.counter.uid == viewerUid
            )
        }
        let viewerRank = rows.first(where: { $0.isViewer })?.rank
        return CrownSeasonBoard(seasonId: seasonId, rows: rows, viewerRank: viewerRank)
    }

    /// Composes the viewer's own stats from their all-time + season counters and
    /// their rich-stats doc, or nil when there is nothing to show yet (no
    /// counter on either board AND no stats doc). `seasonRank` comes from the
    /// season board (``board(counters:viewerUid:names:seasonId:)``'s viewerRank).
    static func personalStats(
        allTime: CrownLeaderboardCounter?,
        season: CrownLeaderboardCounter?,
        seasonRank: Int?,
        rich: CrownUserStatsDoc?
    ) -> CrownPersonalStats? {
        if allTime == nil, season == nil, rich == nil { return nil }
        return CrownPersonalStats(
            points: allTime?.points ?? 0,
            crownsCollected: allTime?.crownsCollected ?? 0,
            seasonRank: seasonRank,
            seasonPoints: season?.points ?? 0,
            seasonCrowns: season?.crownsCollected ?? 0,
            byRarity: rich?.byRarity ?? [:],
            streakCurrent: rich?.streakCurrent ?? 0,
            streakBest: rich?.streakBest ?? 0,
            seasonsWon: rich?.seasonsWon ?? 0,
            rarest: rich?.rarest
        )
    }

    /// A short, stable stand-in when a member's public profile name is missing.
    private static func resolveName(_ uid: String, _ names: [String: String]) -> String {
        if let name = names[uid]?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
            return name
        }
        return String(uid.prefix(8))
    }
}

/// The composed result of one stats read — the viewer's own stats (nil when
/// they have never collected) and this season's board. Mirrors Android's
/// `CrownStatsUiState.Loaded` payload; the coordinator lifts it into a UI state
/// (with the loading/empty/unavailable/failed cases the SwiftUI view switches
/// on).
struct CrownStatsData: Equatable, Sendable {
    /// Nil when the member has never collected a crown (no stats doc / no board
    /// entry) — the page then shows a "collect your first crown" prompt rather
    /// than a wall of zeros.
    let personal: CrownPersonalStats?
    let board: CrownSeasonBoard
}
