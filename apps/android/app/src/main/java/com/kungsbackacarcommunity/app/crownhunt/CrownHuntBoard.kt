package com.kungsbackacarcommunity.app.crownhunt

/**
 * The member-facing stats + leaderboard for the Kronjakt hub page, and the PURE
 * fold that builds them from the raw `crownHuntLeaderboardEntries` /
 * `crownHuntUserStats` documents.
 *
 * The hub page no longer lists crowns (crowns live on the map only) — it shows
 * the member their own standing and this season's top scores. Those come from the
 * read-optimised aggregates the backend maintains on every collection (#710):
 * flat leaderboard COUNTER rows (`{scope}__{uid}` → points + crownsCollected) and
 * a per-member rich-stats doc (streak, rarity breakdown, seasonsWon). No client
 * writes them; the Android read path ([CrownHuntStatsRepository]) only reads.
 *
 * This object is Firebase-free so the RANKING and the composition are pinned by
 * JVM unit tests — the ranking in particular MUST agree with the server's
 * `rankLeaderboard` (and the admin dashboard's `rankLeaderboardCounters`), or the
 * "you are #3" a member reads here would disagree with the authoritative board.
 */

/** One member's raw leaderboard counter for a scope, before ranking + naming. */
data class CrownLeaderboardCounter(
    val uid: String,
    val points: Int,
    val crownsCollected: Int,
)

/** One ranked, name-resolved row shown in the leaderboard list. */
data class CrownLeaderboardRow(
    val rank: Int,
    val uid: String,
    val displayName: String,
    val points: Int,
    val crownsCollected: Int,
    /** True for the signed-in viewer's own row, so the UI can highlight it. */
    val isViewer: Boolean,
)

/** This season's ranked top scores, plus the viewer's own rank in the scope. */
data class CrownSeasonBoard(
    val seasonId: String,
    val rows: List<CrownLeaderboardRow>,
    /**
     * The viewer's rank in this scope, or null if they have not collected this
     * season (or their rank is outside the fetched page — see
     * [CrownHuntBoard.board]).
     */
    val viewerRank: Int?,
)

/** The signed-in member's own Kronjakt statistics for the hub page. */
data class CrownPersonalStats(
    /** Lifetime Kronpoäng from the all-time board (both crown sources count). */
    val points: Int,
    /** Lifetime crowns collected. */
    val crownsCollected: Int,
    /** Rank this season, or null when the viewer is outside the fetched page. */
    val seasonRank: Int?,
    /** Kronpoäng earned this season. */
    val seasonPoints: Int,
    /** Crowns collected this season. */
    val seasonCrowns: Int,
    /** Auto-spawned crowns collected, by rarity (hand-placed crowns are not tiered). */
    val byRarity: Map<CrownRarity, Int>,
    /** Consecutive-day collection streak (Europe/Stockholm days). */
    val streakCurrent: Int,
    /** Best streak ever. */
    val streakBest: Int,
    /** Lifetime season victories (first-place finishes). */
    val seasonsWon: Int,
    /** The rarest auto-spawned crown ever collected, or null if none yet. */
    val rarest: CrownRarity?,
)

/** UI-facing state of the Kronjakt hub stats + leaderboard read. */
sealed interface CrownStatsUiState {
    data object Loading : CrownStatsUiState

    data object Error : CrownStatsUiState

    /**
     * [personal] is null when the member has never collected a crown (no stats
     * doc / no board entry yet) — the page then shows a friendly "collect your
     * first crown" prompt rather than a wall of zeros.
     */
    data class Loaded(
        val personal: CrownPersonalStats?,
        val board: CrownSeasonBoard,
    ) : CrownStatsUiState
}

object CrownHuntBoard {
    /** How many top rows the hub leaderboard shows. */
    const val LEADERBOARD_TOP_N: Int = 10

    /**
     * Ranks [counters] into leaderboard order — the "strictly better" ordering the
     * backend's `rankLeaderboard` uses: points DESC, then crownsCollected DESC,
     * then uid ASC as the final, deterministic tiebreak. Rank is 1-based position.
     *
     * Kept identical to `rankLeaderboardCounters` in the admin read layer so a
     * client's ordering never disagrees with the server's authoritative rank.
     */
    fun rank(counters: List<CrownLeaderboardCounter>): List<Pair<CrownLeaderboardCounter, Int>> =
        counters
            .sortedWith(
                compareByDescending<CrownLeaderboardCounter> { it.points }
                    .thenByDescending { it.crownsCollected }
                    .thenBy { it.uid },
            )
            .mapIndexed { index, counter -> counter to (index + 1) }

    /**
     * Builds the season board from raw counters, resolving each uid's display
     * name from [names] (falling back to a short uid stub when a profile is
     * missing — a private/deleted profile never blanks a row).
     *
     * @param viewerUid the signed-in member, so their row is flagged and their
     *   rank surfaced. [CrownSeasonBoard.viewerRank] is their position IN THE
     *   RANKED PAGE — null when they are not in the fetched top rows. The page is
     *   the community's leaderboard, so for all but the largest boards the viewer
     *   is present; a precise rank beyond the page would cost a second ordered
     *   scan and is intentionally not read here.
     */
    fun board(
        counters: List<CrownLeaderboardCounter>,
        viewerUid: String?,
        names: Map<String, String>,
        seasonId: String,
    ): CrownSeasonBoard {
        val ranked = rank(counters)
        val rows =
            ranked.map { (counter, rank) ->
                CrownLeaderboardRow(
                    rank = rank,
                    uid = counter.uid,
                    displayName = resolveName(counter.uid, names),
                    points = counter.points,
                    crownsCollected = counter.crownsCollected,
                    isViewer = counter.uid == viewerUid,
                )
            }
        val viewerRank = rows.firstOrNull { it.isViewer }?.rank
        return CrownSeasonBoard(seasonId = seasonId, rows = rows, viewerRank = viewerRank)
    }

    /**
     * Composes the viewer's own stats from their all-time + season counters and
     * their rich-stats doc, or null when there is nothing to show yet (no counter
     * on either board AND no stats doc). [seasonRank] comes from the season board
     * ([board]'s viewerRank).
     */
    fun personalStats(
        allTime: CrownLeaderboardCounter?,
        season: CrownLeaderboardCounter?,
        seasonRank: Int?,
        rich: CrownUserStatsDoc?,
    ): CrownPersonalStats? {
        if (allTime == null && season == null && rich == null) return null
        return CrownPersonalStats(
            points = allTime?.points ?: 0,
            crownsCollected = allTime?.crownsCollected ?: 0,
            seasonRank = seasonRank,
            seasonPoints = season?.points ?: 0,
            seasonCrowns = season?.crownsCollected ?: 0,
            byRarity = rich?.byRarity ?: emptyMap(),
            streakCurrent = rich?.streakCurrent ?: 0,
            streakBest = rich?.streakBest ?: 0,
            seasonsWon = rich?.seasonsWon ?: 0,
            rarest = rich?.rarest,
        )
    }

    /** A short, stable stand-in when a member's public profile name is missing. */
    private fun resolveName(uid: String, names: Map<String, String>): String =
        names[uid]?.trim()?.takeIf { it.isNotEmpty() } ?: uid.take(8)
}

/**
 * The subset of a `crownHuntUserStats/{uid}` document the hub page reads. A plain
 * data class (not the Firestore snapshot) so [CrownHuntBoard.personalStats] stays
 * pure and testable; the Firebase repository maps the document onto it.
 *
 * `byRarity` reads an ABSENT bucket as 0 (the writer updates buckets sparsely),
 * and `rarest` is null for a member who has only collected hand-placed crowns —
 * both exactly as the shared contract documents.
 */
data class CrownUserStatsDoc(
    val byRarity: Map<CrownRarity, Int>,
    val streakCurrent: Int,
    val streakBest: Int,
    val seasonsWon: Int,
    val rarest: CrownRarity?,
)
