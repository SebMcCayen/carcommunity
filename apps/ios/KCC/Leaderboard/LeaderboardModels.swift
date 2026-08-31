import Foundation

/// Social LEADERBOARD domain model + pure logic — the iOS port of Android's
/// `leaderboard/LeaderboardBoard.kt`.
///
/// The backend precompute writes ONE client-readable document per scope,
/// `leaderboards/{scope}` where `scope` is `alltime` or a `YYYY-MM`
/// Europe/Stockholm month id (functions/src/leaderboard/leaderboard-core.ts +
/// generator.ts). Each document holds per-CATEGORY arrays of already-ranked,
/// already-name/avatar-resolved rows:
///
/// ```
/// {
///   scope: "alltime",
///   categories: {
///     crownPoints: [{ rank, uid, displayName, avatarPath, value }, ...],
///     distance:    [...],
///     events:      [...],
///     convoys:     [...],
///     waves:       [...],
///     streak:      [...]   // ALL-TIME ONLY (a streak spans months)
///   },
///   generatedAt: <timestamp>
/// }
/// ```
///
/// The server already ranks each array (value DESC, uid ASC), filters
/// opted-out and deleted members, and resolves names/avatars — so the client
/// NEVER re-ranks. It reads the array in the order given and trusts each row's
/// `rank`. This file is the single home for every decision the leaderboard
/// makes that does NOT need Firestore: which scope maps to which document id,
/// which categories a scope publishes (and their render order), how a
/// category's raw value becomes a display magnitude (metres → km, etc.), and
/// the podium/list split. It imports no Firebase, so every edge is unit-tested
/// (``LeaderboardModelTests``). It mirrors the backend's leaderboard-core
/// split and the iOS ``Events`` pure core: the Firebase repository proves the
/// wiring, this proves the assembly.

/// The two boards a member can switch between at the top of the screen — the
/// iOS mirror of Android's `LeaderboardScope`.
enum LeaderboardScope: Hashable, Sendable, CaseIterable {
    /// The never-resetting all-time board (`leaderboards/alltime`).
    case allTime

    /// The current Europe/Stockholm month (`leaderboards/{YYYY-MM}`).
    case thisMonth
}

/// How a category's raw stored `value` is presented — Android's
/// `LeaderboardValueFormat`. The numeric transform lives in
/// ``LeaderboardBoard/displayValue(_:value:)``; the localized template that
/// wraps the resulting number lives in the screen (a contract string), so
/// nothing here hard-codes a label or a unit and both languages stay in the
/// contract.
enum LeaderboardValueFormat: Equatable, Sendable {
    /// Kronpoäng — shown as "N KP" (sv) / "N CP" (en). Raw value is the point
    /// total.
    case crownPoints

    /// Distance — stored in METRES, shown rounded to whole kilometres.
    case distanceKm

    /// A plain count (events attended, convoys led).
    case count

    /// A count of waves sent — shown as "N waves" / "N vinkningar".
    case waves

    /// A day count (the collection streak).
    case days
}

/// The competitive categories, in the exact render order the backend declares
/// (`LEADERBOARD_CATEGORIES`) — Android's `LeaderboardCategory`. ``wireKey`` is
/// the field name inside the document's `categories` map. ``allTimeOnly`` marks
/// `streak`, which the monthly document omits — see
/// ``LeaderboardBoard/categories(for:)``.
enum LeaderboardCategory: Hashable, Sendable, CaseIterable {
    case crownPoints
    case distance
    case events
    case convoys
    case waves
    case streak

    /// The field name inside the document's `categories` map.
    var wireKey: String {
        switch self {
        case .crownPoints: return "crownPoints"
        case .distance: return "distance"
        case .events: return "events"
        case .convoys: return "convoys"
        case .waves: return "waves"
        case .streak: return "streak"
        }
    }

    /// How this category's raw value is presented.
    var format: LeaderboardValueFormat {
        switch self {
        case .crownPoints: return .crownPoints
        case .distance: return .distanceKm
        case .events, .convoys: return .count
        case .waves: return .waves
        case .streak: return .days
        }
    }

    /// True for `streak`, which only the all-time board publishes (a
    /// daily-collection streak spans months, so it has no per-month meaning).
    var isAllTimeOnly: Bool { self == .streak }
}

/// A raw row as read from a category array, before it becomes a UI
/// ``LeaderboardEntry`` — Android's `RawLeaderboardRow`. A plain value type
/// (not the Firestore snapshot) so ``LeaderboardBoard/board(scope:rawByCategory:viewerUid:)``
/// stays pure and testable; the Firebase repository maps each document map
/// onto it.
struct RawLeaderboardRow: Equatable, Sendable {
    let rank: Int
    let uid: String
    let displayName: String
    let avatarPath: String?
    /// The stored magnitude — points, metres, or a count, depending on the
    /// category.
    let value: Double
}

/// One ranked row shown on the board (podium tile or list line) — Android's
/// `LeaderboardEntry`.
struct LeaderboardEntry: Equatable, Sendable, Identifiable {
    /// The server's 1-based published rank (contiguous; opted-out/deleted
    /// removed).
    let rank: Int
    let uid: String
    let displayName: String
    let avatarPath: String?
    /// The raw stored magnitude; format for display via
    /// ``LeaderboardCategory/format``.
    let value: Double
    /// True for the signed-in viewer's own row, so the UI can highlight it.
    let isViewer: Bool

    /// Stable identity for `ForEach`: a member appears at most once per
    /// category, so the uid is unique within a board.
    var id: String { uid }
}

/// One category's ranked rows for the selected scope — Android's
/// `LeaderboardCategoryBoard`.
struct LeaderboardCategoryBoard: Equatable, Sendable, Identifiable {
    let category: LeaderboardCategory
    let entries: [LeaderboardEntry]

    /// A category appears at most once per board, so its wire key is a stable
    /// identity for `ForEach`.
    var id: String { category.wireKey }
}

/// The podium (top three) and the remainder (rank 4 downwards) of a category,
/// split for the two-part rendering — Android's `LeaderboardPodiumSplit`.
/// ``top`` holds AT MOST three entries in rank order; a board with fewer than
/// three rows simply yields a shorter podium and an empty ``rest``.
struct LeaderboardPodiumSplit: Equatable, Sendable {
    let top: [LeaderboardEntry]
    let rest: [LeaderboardEntry]
}

/// Social LEADERBOARD — pure (Firebase-free) core. The iOS counterpart of
/// Android's `LeaderboardBoard` object.
enum LeaderboardBoard {
    /// How many top rows form the podium.
    static let podiumSize = 3

    /// The reserved document id for the never-resetting all-time board.
    static let allTimeDocId = "alltime"

    private static let metresPerKm = 1_000.0

    /// The document id to read for `scope`. All-time is the fixed
    /// ``allTimeDocId``; this-month is the `YYYY-MM` id from `seasonId`
    /// (derived from ``LeaderboardSeasonClock`` so the client and the backend
    /// agree on the month boundary and format).
    ///
    /// `seasonId` is a LAZY autoclosure, evaluated ONLY for the monthly scope
    /// — the all-time board never needs a season id, so resolving one (a
    /// `Date()` + format) for it would be wasted work.
    static func scopeDocId(_ scope: LeaderboardScope, seasonId: @autoclosure () -> String) -> String {
        switch scope {
        case .allTime: return allTimeDocId
        case .thisMonth: return seasonId()
        }
    }

    /// The categories `scope` publishes, in render order. All-time carries
    /// every category; a monthly board omits the all-time-only ones
    /// (`streak`) — exactly the split the backend's
    /// `LEADERBOARD_MONTHLY_CATEGORIES` makes.
    static func categories(for scope: LeaderboardScope) -> [LeaderboardCategory] {
        LeaderboardCategory.allCases.filter { scope == .allTime || !$0.isAllTimeOnly }
    }

    /// The whole board for `scope` from the raw per-category rows keyed by
    /// ``LeaderboardCategory/wireKey``. Iterates ``categories(for:)`` so the
    /// render order and the scope's category set are fixed here, not at the
    /// read site: a missing key yields an empty category (never a dropped
    /// section), and the server's row order and `rank` are preserved verbatim
    /// (the client does not re-rank). `viewerUid` flags the signed-in member's
    /// own row.
    ///
    /// A row without a POSITIVE rank is dropped: rank drives the medal colour,
    /// the podium/list split and the "#N" line, so a rank-0 row would render
    /// as a broken "#0" with no medal. The server always publishes contiguous
    /// 1-based ranks, so this only removes a corrupt/partial row, never a
    /// legitimate one. A blank `displayName` is NOT a drop reason
    /// (``resolveName(_:uid:)`` falls back to a uid stub) — the one field a
    /// row cannot survive without is a positive rank (and a uid, already
    /// required upstream when the raw rows are extracted).
    static func board(
        scope: LeaderboardScope,
        rawByCategory: [String: [RawLeaderboardRow]],
        viewerUid: String?
    ) -> [LeaderboardCategoryBoard] {
        categories(for: scope).map { category in
            let rows = rawByCategory[category.wireKey] ?? []
            let entries = rows.filter { $0.rank > 0 }.map { row in
                LeaderboardEntry(
                    rank: row.rank,
                    uid: row.uid,
                    displayName: resolveName(row.displayName, uid: row.uid),
                    avatarPath: row.avatarPath,
                    value: row.value,
                    isViewer: viewerUid != nil && row.uid == viewerUid
                )
            }
            return LeaderboardCategoryBoard(category: category, entries: entries)
        }
    }

    /// Splits a category's `entries` into the podium (first ``podiumSize``, in
    /// rank order) and the remainder. Assumes `entries` is already in the
    /// server's ranked order (it always is — the document arrays are
    /// pre-sorted).
    static func podiumSplit(_ entries: [LeaderboardEntry]) -> LeaderboardPodiumSplit {
        LeaderboardPodiumSplit(
            top: Array(entries.prefix(podiumSize)),
            rest: Array(entries.dropFirst(podiumSize))
        )
    }

    /// The whole-number magnitude shown for a raw `value` under `format`:
    ///  - ``LeaderboardValueFormat/distanceKm`` converts metres → kilometres,
    ///    rounded to the nearest whole km (matching the badge ladder's "N km"
    ///    rendering);
    ///  - every other format rounds the value to the nearest whole unit.
    ///
    /// A negative or non-finite value clamps to 0 — a board never shows a
    /// negative standing. The localized unit template (e.g. "%lld km",
    /// "%lld CP") is applied by the screen; this returns only the number to
    /// place in it.
    static func displayValue(_ format: LeaderboardValueFormat, value: Double) -> Int64 {
        guard value.isFinite, value > 0 else { return 0 }
        switch format {
        case .distanceKm: return Int64((value / metresPerKm).rounded())
        default: return Int64(value.rounded())
        }
    }

    /// A short, stable stand-in if a server row ever carries a blank name (it
    /// should not).
    private static func resolveName(_ displayName: String, uid: String) -> String {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? String(uid.prefix(8)) : trimmed
    }
}

/// The current Kronjakt SEASON id, client side — the iOS port of Android's
/// `crownhunt/CrownSeasonClock.kt` (there is no shared iOS season clock yet, so
/// the leaderboard carries the small piece it needs).
///
/// A season is one calendar MONTH in Europe/Stockholm and its id is `YYYY-MM`,
/// mirroring `seasonIdForInstant` in
/// `functions/src/crownHunt/crown-hunt-stats-core.ts`. The client needs it only
/// to pick WHICH monthly board document to read; the backend remains
/// authoritative for every count, so a client that computed the wrong month
/// (it will not — the zone and format are identical) would at worst read an
/// empty board, never award a point. Pure over an injected date + time zone,
/// so the month-boundary behaviour is unit tested off-device.
enum LeaderboardSeasonClock {
    private static let stockholm = TimeZone(identifier: "Europe/Stockholm")

    /// The `YYYY-MM` season id `date` falls in, in `zone` (default Stockholm,
    /// falling back to UTC only if that identifier is somehow unavailable).
    ///
    /// The formatter pins `en_US_POSIX` so the digits are always ASCII: the
    /// backend's doc ids are a plain `YYYY-MM`, and a device whose locale uses
    /// a non-Latin numbering system would otherwise format a season id that
    /// does not exist and read an empty board.
    static func seasonId(for date: Date = Date(), zone: TimeZone? = stockholm) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = zone ?? TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM"
        return formatter.string(from: date)
    }
}
