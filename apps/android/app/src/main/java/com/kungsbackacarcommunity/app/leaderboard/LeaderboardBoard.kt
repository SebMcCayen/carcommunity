package com.kungsbackacarcommunity.app.leaderboard

import kotlin.math.roundToLong

/**
 * Social LEADERBOARD — pure (Android-free) core.
 *
 * The backend precompute writes ONE client-readable document per scope,
 * `leaderboards/{scope}` where `scope` is `alltime` or a `YYYY-MM` Europe/Stockholm
 * month id (see functions/src/leaderboard/leaderboard-core.ts + generator.ts). Each
 * document holds per-CATEGORY arrays of already-ranked, already-name/avatar-resolved
 * rows:
 *
 * ```
 * {
 *   scope: "alltime",
 *   categories: {
 *     crownPoints: [{ rank, uid, displayName, avatarPath, value }, ...],
 *     distance:    [...],
 *     events:      [...],
 *     convoys:     [...],
 *     streak:      [...]   // ALL-TIME ONLY (a streak spans months)
 *   },
 *   generatedAt: <timestamp>
 * }
 * ```
 *
 * The server already ranks each array (value DESC, uid ASC), filters opted-out and
 * deleted members, and resolves names/avatars — so the client NEVER re-ranks. It
 * reads the array in the order given and trusts each row's `rank`. This object is
 * the single home for every decision the Android leaderboard makes that does NOT
 * need Firestore: which scope maps to which document id, which categories a scope
 * publishes (and their render order), how a category's raw value is turned into a
 * display magnitude (metres → km, etc.), and the podium/list split. It imports no
 * Firebase, so every edge is JVM-unit-tested (LeaderboardBoardTest).
 *
 * It mirrors the backend's leaderboard-core split and the app's own CrownHuntBoard:
 * the Firebase repository proves the wiring, this proves the assembly.
 */

/** The two boards a member can switch between at the top of the screen. */
enum class LeaderboardScope {
    /** The never-resetting all-time board (`leaderboards/alltime`). */
    ALL_TIME,

    /** The current Europe/Stockholm month (`leaderboards/{YYYY-MM}`). */
    THIS_MONTH,
}

/**
 * How a category's raw stored `value` is presented. The numeric transform lives in
 * [LeaderboardBoard.displayValue]; the localized template that wraps the resulting
 * number lives in the screen (a resource string), so nothing here hard-codes a
 * label or a unit and both languages stay in the contract.
 */
enum class LeaderboardValueFormat {
    /** Kronpoäng — shown as "N KP" (sv) / "N CP" (en). Raw value is the point total. */
    CROWN_POINTS,

    /** Distance — stored in METRES, shown rounded to whole kilometres. */
    DISTANCE_KM,

    /** A plain count (events attended, convoys led). */
    COUNT,

    /** A day count (the collection streak). */
    DAYS,
}

/**
 * The competitive categories, in the exact render order the backend declares
 * (LEADERBOARD_CATEGORIES). [wireKey] is the field name inside the document's
 * `categories` map. [allTimeOnly] marks `streak`, which the monthly document omits
 * — see [LeaderboardBoard.categoriesFor].
 */
enum class LeaderboardCategory(
    val wireKey: String,
    val format: LeaderboardValueFormat,
    val allTimeOnly: Boolean,
) {
    CROWN_POINTS("crownPoints", LeaderboardValueFormat.CROWN_POINTS, allTimeOnly = false),
    DISTANCE("distance", LeaderboardValueFormat.DISTANCE_KM, allTimeOnly = false),
    EVENTS("events", LeaderboardValueFormat.COUNT, allTimeOnly = false),
    CONVOYS("convoys", LeaderboardValueFormat.COUNT, allTimeOnly = false),
    STREAK("streak", LeaderboardValueFormat.DAYS, allTimeOnly = true),
}

/**
 * A raw row as read from a category array, before it becomes a UI [LeaderboardEntry].
 * A plain data class (not the Firestore snapshot) so [LeaderboardBoard.board] stays
 * pure and testable; the Firebase repository maps each document map onto it.
 */
data class RawLeaderboardRow(
    val rank: Int,
    val uid: String,
    val displayName: String,
    val avatarPath: String?,
    /** The stored magnitude — points, metres, or a count, depending on the category. */
    val value: Double,
)

/** One ranked row shown on the board (podium tile or list line). */
data class LeaderboardEntry(
    /** The server's 1-based published rank (contiguous; opted-out/deleted removed). */
    val rank: Int,
    val uid: String,
    val displayName: String,
    val avatarPath: String?,
    /** The raw stored magnitude; format for display via [LeaderboardCategory.format]. */
    val value: Double,
    /** True for the signed-in viewer's own row, so the UI can highlight it. */
    val isViewer: Boolean,
)

/** One category's ranked rows for the selected scope. */
data class LeaderboardCategoryBoard(
    val category: LeaderboardCategory,
    val entries: List<LeaderboardEntry>,
)

/**
 * The podium (top three) and the remainder (rank 4 downwards) of a category, split
 * for the two-part rendering. [top] holds AT MOST three entries in rank order; a
 * board with fewer than three rows simply yields a shorter podium and an empty
 * [rest].
 */
data class LeaderboardPodiumSplit(
    val top: List<LeaderboardEntry>,
    val rest: List<LeaderboardEntry>,
)

/** UI-facing state of the leaderboard read for one scope. */
sealed interface LeaderboardUiState {
    data object Loading : LeaderboardUiState

    data object Error : LeaderboardUiState

    /**
     * [categories] is the scope's category boards in render order. A category with
     * no entries yet is still present (with an empty list) so the screen can show a
     * friendly per-category empty state rather than silently dropping the section.
     */
    data class Loaded(
        val scope: LeaderboardScope,
        val categories: List<LeaderboardCategoryBoard>,
    ) : LeaderboardUiState
}

object LeaderboardBoard {
    /** How many top rows form the podium. */
    const val PODIUM_SIZE: Int = 3

    /** The reserved document id for the never-resetting all-time board. */
    const val ALL_TIME_DOC_ID: String = "alltime"

    /**
     * The document id to read for [scope]. All-time is the fixed [ALL_TIME_DOC_ID];
     * this-month is the `YYYY-MM` id from [seasonId] (derived from
     * [com.kungsbackacarcommunity.app.crownhunt.CrownSeasonClock] so the client and
     * the backend agree on the month boundary and format).
     *
     * [seasonId] is a LAZY provider, evaluated ONLY for the monthly scope — the
     * all-time board never needs a season id, so resolving one (an `Instant.now()`
     * + format) for it would be wasted work.
     */
    fun scopeDocId(scope: LeaderboardScope, seasonId: () -> String): String =
        when (scope) {
            LeaderboardScope.ALL_TIME -> ALL_TIME_DOC_ID
            LeaderboardScope.THIS_MONTH -> seasonId()
        }

    /**
     * The categories [scope] publishes, in render order. All-time carries every
     * category; a monthly board omits the all-time-only ones (`streak`), because a
     * daily-collection streak spans months and has no per-month meaning — exactly the
     * split the backend's LEADERBOARD_MONTHLY_CATEGORIES makes.
     */
    fun categoriesFor(scope: LeaderboardScope): List<LeaderboardCategory> =
        LeaderboardCategory.entries.filter { scope == LeaderboardScope.ALL_TIME || !it.allTimeOnly }

    /**
     * The whole board for [scope] from the raw per-category rows keyed by
     * [LeaderboardCategory.wireKey]. Iterates [categoriesFor] so the render order and
     * the scope's category set are fixed here, not at the read site: a missing key
     * yields an empty category (never a dropped section), and the server's row order
     * and `rank` are preserved verbatim (the client does not re-rank). [viewerUid]
     * flags the signed-in member's own row.
     *
     * A row without a POSITIVE rank is dropped: rank drives the medal colour, the
     * podium/list split and the "#N" line, so a rank-0 row would render as a broken
     * "#0" with no medal. The server always publishes contiguous 1-based ranks, so
     * this only removes a corrupt/partial row, never a legitimate one. A blank
     * `displayName` is NOT a drop reason — [resolveName] falls back to a uid stub —
     * so the one field a row cannot survive without is a positive rank (and a uid,
     * already required upstream when the raw rows are extracted).
     */
    fun board(
        scope: LeaderboardScope,
        rawByCategory: Map<String, List<RawLeaderboardRow>>,
        viewerUid: String?,
    ): List<LeaderboardCategoryBoard> =
        categoriesFor(scope).map { category ->
            val rows = rawByCategory[category.wireKey].orEmpty()
            LeaderboardCategoryBoard(
                category = category,
                entries =
                    rows
                        .filter { it.rank > 0 }
                        .map { row ->
                            LeaderboardEntry(
                                rank = row.rank,
                                uid = row.uid,
                                displayName = resolveName(row.displayName, row.uid),
                                avatarPath = row.avatarPath,
                                value = row.value,
                                isViewer = viewerUid != null && row.uid == viewerUid,
                            )
                        },
            )
        }

    /**
     * Splits a category's [entries] into the podium (first [PODIUM_SIZE], in rank
     * order) and the remainder. Assumes [entries] is already in the server's ranked
     * order (it always is — the document arrays are pre-sorted).
     */
    fun podiumSplit(entries: List<LeaderboardEntry>): LeaderboardPodiumSplit =
        LeaderboardPodiumSplit(
            top = entries.take(PODIUM_SIZE),
            rest = entries.drop(PODIUM_SIZE),
        )

    /**
     * The whole-number magnitude shown for a raw [value] under [format]:
     *  - [LeaderboardValueFormat.DISTANCE_KM] converts metres → kilometres, rounded to
     *    the nearest whole km (matching the badge ladder's "N km" rendering);
     *  - every other format rounds the value to the nearest whole unit.
     *
     * A negative or non-finite value clamps to 0 — a board never shows a negative
     * standing. The localized unit template (e.g. "%d km", "%d CP") is applied by the
     * screen; this returns only the number to place in it.
     */
    fun displayValue(format: LeaderboardValueFormat, value: Double): Long {
        if (!value.isFinite() || value <= 0.0) return 0L
        return when (format) {
            LeaderboardValueFormat.DISTANCE_KM -> (value / METRES_PER_KM).roundToLong()
            else -> value.roundToLong()
        }
    }

    /** A short, stable stand-in if a server row ever carries a blank name (it should not). */
    private fun resolveName(displayName: String, uid: String): String =
        displayName.trim().takeIf { it.isNotEmpty() } ?: uid.take(8)

    private const val METRES_PER_KM = 1_000.0
}
