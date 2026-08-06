package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import com.kungsbackacarcommunity.app.profile.FirebaseLiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * [CrownHuntStatsRepository] backed by rules-gated Firestore reads of the #710
 * aggregates:
 *
 *  - `crownHuntLeaderboardEntries` (scope `YYYY-MM`) — this season's ranked board,
 *    read `where scope == season orderBy points desc, crownsCollected desc limit N`
 *    (the deployed composite index `scope ASC, points DESC, crownsCollected DESC`),
 *    plus the viewer's own `alltime__{uid}` and `{season}__{uid}` counter docs.
 *  - `crownHuntUserStats/{uid}` — the viewer's own streak / rarity / seasonsWon,
 *    readable only by the owner.
 *
 * Display names are resolved from the members' public `users/{uid}` profiles via
 * the shared [LiveProfileRepository], best-effort: a missing profile falls back to
 * a short uid stub and never blocks the board. Nothing here writes — every one of
 * these collections is backend-trigger-owned.
 */
class FirebaseCrownHuntStatsRepository(
    private val firestore: FirebaseFirestore,
    private val liveProfiles: LiveProfileRepository,
    private val seasonIdProvider: () -> String = { CrownSeasonClock.currentSeasonId() },
) : CrownHuntStatsRepository {

    override fun observeStats(uid: String): Flow<CrownStatsUiState> =
        flow {
            emit(CrownStatsUiState.Loading)
            val seasonId = seasonIdProvider()

            // This season's ranked page. Shaped to the deployed composite index;
            // all conditions on the query, as the security rule expects.
            val boardSnap =
                firestore
                    .collection(LEADERBOARD)
                    .whereEqualTo(FIELD_SCOPE, seasonId)
                    .orderBy(FIELD_POINTS, Query.Direction.DESCENDING)
                    .orderBy(FIELD_CROWNS, Query.Direction.DESCENDING)
                    .limit(CrownHuntBoard.LEADERBOARD_TOP_N.toLong())
                    .get()
                    .awaitOrThrow { "crown leaderboard query failed without a cause" }
            val counters = boardSnap.documents.mapNotNull { it.toCounter() }

            // The viewer's own counters (may be absent — they have never
            // collected in that scope) and rich stats.
            val seasonEntry =
                firestore.collection(LEADERBOARD).document(entryId(seasonId, uid))
                    .get().awaitOrThrow { "crown season entry read failed" }
            val allTimeEntry =
                firestore.collection(LEADERBOARD)
                    .document(entryId(CrownSeasonClock.ALL_TIME_SCOPE, uid))
                    .get().awaitOrThrow { "crown all-time entry read failed" }
            val statsDoc =
                firestore.collection(USER_STATS).document(uid)
                    .get().awaitOrThrow { "crown user-stats read failed" }

            // Resolve names for the ranked page and the viewer.
            val uids = counters.map { it.uid }.toMutableSet().apply { add(uid) }
            val names =
                liveProfiles.loadProfiles(uids)
                    .mapNotNull { (id, profile) ->
                        profile.displayName?.let { id to it }
                    }
                    .toMap()

            val board = CrownHuntBoard.board(counters, uid, names, seasonId)
            val personal =
                CrownHuntBoard.personalStats(
                    allTime = allTimeEntry.toCounter(),
                    season = seasonEntry.toCounter(),
                    seasonRank = board.viewerRank,
                    rich = statsDoc.toUserStats(),
                )
            emit(CrownStatsUiState.Loaded(personal = personal, board = board))
        }.catch { emit(CrownStatsUiState.Error) }

    companion object {
        private const val LEADERBOARD = "crownHuntLeaderboardEntries"
        private const val USER_STATS = "crownHuntUserStats"
        private const val FIELD_SCOPE = "scope"
        private const val FIELD_POINTS = "points"
        private const val FIELD_CROWNS = "crownsCollected"

        /** Mirrors the backend `leaderboardEntryRef` id: `{scope}__{uid}`. */
        private fun entryId(scope: String, uid: String): String = "${scope}__$uid"

        /**
         * A stats repository, or null in a config-less/CI build (no Firebase) — so
         * the hub page simply shows its loading/empty affordance rather than
         * crashing, exactly as the crown map layer degrades.
         */
        fun createIfAvailable(context: Context): CrownHuntStatsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseCrownHuntStatsRepository(
                FirebaseFirestore.getInstance(),
                // Reuse the shared, cached live-profile repo so name resolution
                // shares the app's one read scope rather than opening a second.
                FirebaseLiveProfileRepository.sharedOrEmpty(context),
            )
        }
    }
}

/**
 * Maps a `crownHuntLeaderboardEntries/{scope}__{uid}` document to a counter, or
 * null when it does not exist or is missing its uid (a document that cannot be
 * ranked is dropped rather than shown as an anonymous zero row).
 */
private fun DocumentSnapshot.toCounter(): CrownLeaderboardCounter? {
    if (!exists()) return null
    val uid = getString("uid") ?: return null
    return CrownLeaderboardCounter(
        uid = uid,
        points = (get("points") as? Number)?.toInt() ?: 0,
        crownsCollected = (get("crownsCollected") as? Number)?.toInt() ?: 0,
    )
}

/** Maps a `crownHuntUserStats/{uid}` document to the hub's read subset. */
private fun DocumentSnapshot.toUserStats(): CrownUserStatsDoc? {
    if (!exists()) return null
    @Suppress("UNCHECKED_CAST")
    val rarityMap = get("byRarity") as? Map<String, Any?> ?: emptyMap()
    val byRarity =
        CrownRarity.entries.mapNotNull { rarity ->
            (rarityMap[rarity.wire] as? Number)?.toInt()?.takeIf { it > 0 }?.let { rarity to it }
        }.toMap()
    return CrownUserStatsDoc(
        byRarity = byRarity,
        // The streak fields are written under these keys by the ledger trigger.
        streakCurrent = (get("collectionStreakCurrent") as? Number)?.toInt() ?: 0,
        streakBest = (get("collectionStreakBest") as? Number)?.toInt() ?: 0,
        seasonsWon = (get("seasonsWon") as? Number)?.toInt() ?: 0,
        rarest = CrownRarity.fromWire(getString("rarestRarity")),
    )
}
