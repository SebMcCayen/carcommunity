import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``CrownHuntStatsRepository`` backed by rules-gated Firestore reads of the
/// read-optimised aggregates — the iOS port of Android's
/// `FirebaseCrownHuntStatsRepository`.
///
///  - `crownHuntLeaderboardEntries` (scope `YYYY-MM`) — this season's ranked
///    board, read `where scope == season orderBy points desc, crownsCollected
///    desc limit N` (the deployed composite index), plus the viewer's own
///    `alltime__{uid}` and `{season}__{uid}` counter docs by id.
///  - `crownHuntUserStats/{uid}` — the viewer's own streak / rarity / seasonsWon,
///    readable only by the owner.
///
/// Display names are resolved from the members' public `users/{uid}` profiles,
/// best-effort: a missing profile falls back to a short uid stub and never
/// blocks the board. The read is one-shot per subscription (the aggregates move
/// only when someone collects a crown). Nothing here writes — every one of
/// these collections is backend-trigger-owned. Construction is guarded
/// (``createIfAvailable()`` returns nil without Firebase).
final class FirebaseCrownHuntStatsRepository: CrownHuntStatsRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let seasonIdProvider: @Sendable () -> String

    private init(
        firestore: Firestore,
        seasonIdProvider: @escaping @Sendable () -> String
    ) {
        self.firestore = firestore
        self.seasonIdProvider = seasonIdProvider
    }

    func stats(uid: String) -> AsyncStream<CrownStatsSnapshot> {
        AsyncStream { continuation in
            let task = Task {
                do {
                    let snapshot = try await self.readStats(uid: uid)
                    continuation.yield(snapshot)
                } catch {
                    continuation.yield(.failed(code: CrownHuntFirestore.statusName(error)))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func readStats(uid: String) async throws -> CrownStatsSnapshot {
        let seasonId = seasonIdProvider()

        // This season's ranked page. Shaped to the deployed composite index;
        // all conditions on the query, as the security rule expects.
        let boardSnap = try await firestore
            .collection(Self.leaderboard)
            .whereField(Self.scopeField, isEqualTo: seasonId)
            .order(by: Self.pointsField, descending: true)
            .order(by: Self.crownsField, descending: true)
            .limit(to: CrownHuntBoard.leaderboardTopN)
            .getDocuments()
        let counters = boardSnap.documents.compactMap(Self.counter(from:))

        // The viewer's own counters (may be absent) and rich stats — three
        // independent reads, fetched concurrently rather than sequentially.
        async let seasonEntryTask = firestore
            .collection(Self.leaderboard).document(Self.entryId(seasonId, uid)).getDocument()
        async let allTimeEntryTask = firestore
            .collection(Self.leaderboard)
            .document(Self.entryId(CrownSeasonClock.allTimeScope, uid))
            .getDocument()
        async let statsDocTask = firestore
            .collection(Self.userStats).document(uid).getDocument()
        let (seasonEntry, allTimeEntry, statsDoc) = try await (
            seasonEntryTask, allTimeEntryTask, statsDocTask
        )

        // Resolve names for the ranked page and the viewer, best-effort.
        var uids = Set(counters.map { $0.uid })
        uids.insert(uid)
        let names = await resolveNames(uids)

        let board = CrownHuntBoard.board(
            counters: counters, viewerUid: uid, names: names, seasonId: seasonId
        )
        let personal = CrownHuntBoard.personalStats(
            allTime: Self.counter(from: allTimeEntry),
            season: Self.counter(from: seasonEntry),
            seasonRank: board.viewerRank,
            rich: Self.userStats(from: statsDoc)
        )
        return .loaded(CrownStatsData(personal: personal, board: board))
    }

    /// Best-effort display-name resolution: reads each public `users/{uid}`
    /// profile by id, dropping any that error or lack a name. Bounded to the
    /// ranked page (<= LEADERBOARD_TOP_N) plus the viewer, and fetched
    /// concurrently (a `withTaskGroup` fan-out, not a sequential loop) so the
    /// stats read's latency is one round trip, not the sum of up to
    /// LEADERBOARD_TOP_N + 1 of them.
    ///
    /// Genuinely best-effort: a single profile read failing (deleted account,
    /// a transient permission hiccup) is swallowed here rather than thrown, so
    /// it degrades that one row to the uid stub instead of failing the whole
    /// stats read.
    private func resolveNames(_ uids: Set<String>) async -> [String: String] {
        await withTaskGroup(of: (uid: String, name: String?).self) { group in
            for uid in uids {
                group.addTask {
                    guard
                        let document = try? await self.firestore.collection(Self.users)
                            .document(uid).getDocument()
                    else { return (uid, nil) }
                    return (uid, document.get(Self.displayNameField) as? String)
                }
            }
            var names: [String: String] = [:]
            for await (uid, name) in group {
                if let name { names[uid] = name }
            }
            return names
        }
    }

    // MARK: - Mapping

    /// Maps a `crownHuntLeaderboardEntries/{scope}__{uid}` document to a counter,
    /// or nil when it does not exist or is missing its uid.
    static func counter(from document: DocumentSnapshot) -> CrownLeaderboardCounter? {
        guard document.exists, let uid = document.get("uid") as? String else { return nil }
        return CrownLeaderboardCounter(
            uid: uid,
            points: (document.get(pointsField) as? NSNumber)?.intValue ?? 0,
            crownsCollected: (document.get(crownsField) as? NSNumber)?.intValue ?? 0
        )
    }

    /// Maps a `crownHuntUserStats/{uid}` document to the hub's read subset.
    static func userStats(from document: DocumentSnapshot) -> CrownUserStatsDoc? {
        guard document.exists else { return nil }
        let rarityMap = document.get("byRarity") as? [String: Any] ?? [:]
        var byRarity: [CrownRarity: Int] = [:]
        for rarity in CrownRarity.allCases {
            if let count = (rarityMap[rarity.wire] as? NSNumber)?.intValue, count > 0 {
                byRarity[rarity] = count
            }
        }
        return CrownUserStatsDoc(
            byRarity: byRarity,
            // The streak fields are written under these keys by the ledger trigger.
            streakCurrent: (document.get("collectionStreakCurrent") as? NSNumber)?.intValue ?? 0,
            streakBest: (document.get("collectionStreakBest") as? NSNumber)?.intValue ?? 0,
            seasonsWon: (document.get("seasonsWon") as? NSNumber)?.intValue ?? 0,
            rarest: CrownRarity.fromWire(document.get("rarestRarity") as? String)
        )
    }

    /// Mirrors the backend `leaderboardEntryRef` id: `{scope}__{uid}`.
    private static func entryId(_ scope: String, _ uid: String) -> String { "\(scope)__\(uid)" }

    // MARK: - Factory

    private static let leaderboard = "crownHuntLeaderboardEntries"
    private static let userStats = "crownHuntUserStats"
    private static let users = "users"
    private static let scopeField = "scope"
    private static let pointsField = "points"
    private static let crownsField = "crownsCollected"
    private static let displayNameField = "displayName"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseCrownHuntStatsRepository?

    /// A stats repository, or nil in a config-less/CI build (no Firebase) — so
    /// the hub shows its unavailable affordance rather than crashing, exactly as
    /// every other Firebase-backed surface degrades. Honors the
    /// `FIREBASE_FIRESTORE_EMULATOR_HOST` seam.
    static func createIfAvailable(
        seasonIdProvider: @escaping @Sendable () -> String = { CrownSeasonClock.currentSeasonId() }
    ) -> CrownHuntStatsRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = CrownHuntFirebase.firestore()
        let repository = FirebaseCrownHuntStatsRepository(
            firestore: firestore, seasonIdProvider: seasonIdProvider
        )
        cached = repository
        return repository
    }
}
