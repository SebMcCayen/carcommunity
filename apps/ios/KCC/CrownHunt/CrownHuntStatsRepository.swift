import Foundation

/// One settled emission of the Crown-Hunt stats read — the viewer's own stats
/// and this season's board, or a failure. A repository stream only emits
/// SETTLED results; the coordinator supplies the loading state before the first
/// emission (the same split the events feature uses, and that Android gets from
/// `collectAsState(initial = Loading)`).
enum CrownStatsSnapshot: Equatable, Sendable {
    /// The read failed. `code` is the bare Firestore status name when one was
    /// available (`PERMISSION_DENIED` for an undeployed rule,
    /// `FAILED_PRECONDITION` for a missing composite index, `UNAVAILABLE` when
    /// offline) — a stable, PII-safe diagnosis, never the exception text.
    case failed(code: String?)
    /// The viewer's stats (nil until they have collected) + this season's board.
    case loaded(CrownStatsData)
}

/// Read-only access to the member's own Kronjakt statistics and the current
/// season's leaderboard, for the hub + season-standings surfaces — the iOS port
/// of Android's `CrownHuntStatsRepository`.
///
/// Firebase-free protocol so the coordinator + screens are unit-testable with a
/// fake. The one implementation (``FirebaseCrownHuntStatsRepository``) is a
/// direct, rules-gated Firestore read of the read-optimised aggregates:
/// `crownHuntLeaderboardEntries` (this season's ranked page + the viewer's own
/// alltime/season counters) and `crownHuntUserStats/{uid}` (the viewer's rich
/// stats). Those collections expose no callable and every write rule is
/// `false` (backend triggers own them), so there is nothing to write and
/// nothing for the client to compute beyond ranking + display-name resolution.
protocol CrownHuntStatsRepository: AnyObject, Sendable {
    /// A one-shot read per subscription of the viewer's stats + this season's
    /// board. The aggregates change slowly (only when someone collects a crown)
    /// so the surface reads them on open rather than holding a live listener
    /// open. Each call returns a fresh stream that emits exactly one settled
    /// value, then finishes.
    func stats(uid: String) -> AsyncStream<CrownStatsSnapshot>
}
