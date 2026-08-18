package com.kungsbackacarcommunity.app.leaderboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure leaderboard assembly: scope → document id, the scope's category
 * SET and ORDER (streak is all-time only), the metres→km / count value transforms,
 * the podium/list split, and the raw→UI mapping (server order + rank trusted,
 * viewer flagged). No Firestore, no clock.
 */
class LeaderboardBoardTest {

    // ── scopeDocId ──────────────────────────────────────────────────────────

    @Test
    fun allTimeScopeReadsTheReservedDocId() {
        assertEquals("alltime", LeaderboardBoard.scopeDocId(LeaderboardScope.ALL_TIME, "2026-08"))
    }

    @Test
    fun thisMonthScopeReadsTheSeasonId() {
        assertEquals("2026-08", LeaderboardBoard.scopeDocId(LeaderboardScope.THIS_MONTH, "2026-08"))
    }

    // ── categoriesFor ───────────────────────────────────────────────────────

    @Test
    fun allTimeCategoriesAreTheFullSetInDeclaredOrder() {
        assertEquals(
            listOf(
                LeaderboardCategory.CROWN_POINTS,
                LeaderboardCategory.DISTANCE,
                LeaderboardCategory.EVENTS,
                LeaderboardCategory.CONVOYS,
                LeaderboardCategory.STREAK,
            ),
            LeaderboardBoard.categoriesFor(LeaderboardScope.ALL_TIME),
        )
    }

    @Test
    fun monthlyCategoriesOmitStreakButKeepOrder() {
        val monthly = LeaderboardBoard.categoriesFor(LeaderboardScope.THIS_MONTH)
        assertEquals(
            listOf(
                LeaderboardCategory.CROWN_POINTS,
                LeaderboardCategory.DISTANCE,
                LeaderboardCategory.EVENTS,
                LeaderboardCategory.CONVOYS,
            ),
            monthly,
        )
        assertFalse(monthly.contains(LeaderboardCategory.STREAK))
    }

    // ── displayValue ────────────────────────────────────────────────────────

    @Test
    fun distanceValueConvertsMetresToWholeKilometresRounded() {
        // 12 490 m → 12 km, 12 500 m → 13 km (round-half-up via roundToLong).
        assertEquals(12L, LeaderboardBoard.displayValue(LeaderboardValueFormat.DISTANCE_KM, 12_490.0))
        assertEquals(13L, LeaderboardBoard.displayValue(LeaderboardValueFormat.DISTANCE_KM, 12_500.0))
    }

    @Test
    fun crownPointsAndCountsRoundToWholeUnits() {
        assertEquals(1234L, LeaderboardBoard.displayValue(LeaderboardValueFormat.CROWN_POINTS, 1234.0))
        assertEquals(7L, LeaderboardBoard.displayValue(LeaderboardValueFormat.COUNT, 6.6))
        assertEquals(5L, LeaderboardBoard.displayValue(LeaderboardValueFormat.DAYS, 5.0))
    }

    @Test
    fun nonPositiveOrNonFiniteValueClampsToZero() {
        assertEquals(0L, LeaderboardBoard.displayValue(LeaderboardValueFormat.CROWN_POINTS, -5.0))
        assertEquals(0L, LeaderboardBoard.displayValue(LeaderboardValueFormat.DISTANCE_KM, 0.0))
        assertEquals(0L, LeaderboardBoard.displayValue(LeaderboardValueFormat.COUNT, Double.NaN))
        assertEquals(
            0L,
            LeaderboardBoard.displayValue(LeaderboardValueFormat.DISTANCE_KM, Double.POSITIVE_INFINITY),
        )
    }

    // ── podiumSplit ─────────────────────────────────────────────────────────

    @Test
    fun podiumSplitTakesTopThreeThenRest() {
        val entries = (1..10).map { entry(rank = it, uid = "u$it") }
        val split = LeaderboardBoard.podiumSplit(entries)
        assertEquals(listOf(1, 2, 3), split.top.map { it.rank })
        assertEquals((4..10).toList(), split.rest.map { it.rank })
    }

    @Test
    fun podiumSplitWithFewerThanThreeYieldsShortPodiumAndEmptyRest() {
        val entries = listOf(entry(rank = 1, uid = "a"), entry(rank = 2, uid = "b"))
        val split = LeaderboardBoard.podiumSplit(entries)
        assertEquals(2, split.top.size)
        assertTrue(split.rest.isEmpty())
    }

    @Test
    fun podiumSplitOfEmptyBoardIsEmpty() {
        val split = LeaderboardBoard.podiumSplit(emptyList())
        assertTrue(split.top.isEmpty())
        assertTrue(split.rest.isEmpty())
    }

    // ── board mapping ───────────────────────────────────────────────────────

    @Test
    fun boardPreservesServerOrderAndRankAndFlagsTheViewer() {
        val raw =
            mapOf(
                "crownPoints" to
                    listOf(
                        RawLeaderboardRow(1, "alice", "Alice", "avatars/alice", 900.0),
                        RawLeaderboardRow(2, "bob", "Bob", null, 500.0),
                    ),
            )
        val boards = LeaderboardBoard.board(LeaderboardScope.ALL_TIME, raw, viewerUid = "bob")
        val crown = boards.first { it.category == LeaderboardCategory.CROWN_POINTS }
        assertEquals(listOf("alice", "bob"), crown.entries.map { it.uid })
        assertEquals(listOf(1, 2), crown.entries.map { it.rank })
        assertFalse(crown.entries[0].isViewer)
        assertTrue(crown.entries[1].isViewer)
        assertEquals("avatars/alice", crown.entries[0].avatarPath)
        assertNull(crown.entries[1].avatarPath)
    }

    @Test
    fun boardIncludesEveryScopeCategoryEvenWhenAbsentFromTheDocument() {
        // Only crownPoints present; the other all-time categories must still appear
        // as empty boards so the screen can render a per-category empty state.
        val boards =
            LeaderboardBoard.board(
                LeaderboardScope.ALL_TIME,
                rawByCategory = mapOf("crownPoints" to listOf(RawLeaderboardRow(1, "a", "A", null, 1.0))),
                viewerUid = null,
            )
        assertEquals(LeaderboardBoard.categoriesFor(LeaderboardScope.ALL_TIME), boards.map { it.category })
        assertTrue(boards.first { it.category == LeaderboardCategory.DISTANCE }.entries.isEmpty())
    }

    @Test
    fun monthlyBoardNeverProducesAStreakCategoryEvenIfTheDocumentCarriesOne() {
        val boards =
            LeaderboardBoard.board(
                LeaderboardScope.THIS_MONTH,
                rawByCategory = mapOf("streak" to listOf(RawLeaderboardRow(1, "a", "A", null, 9.0))),
                viewerUid = null,
            )
        assertFalse(boards.any { it.category == LeaderboardCategory.STREAK })
    }

    @Test
    fun blankDisplayNameFallsBackToAUidStub() {
        val boards =
            LeaderboardBoard.board(
                LeaderboardScope.ALL_TIME,
                rawByCategory = mapOf("distance" to listOf(RawLeaderboardRow(1, "abcdef1234", "  ", null, 5000.0))),
                viewerUid = null,
            )
        assertEquals("abcdef12", boards.first { it.category == LeaderboardCategory.DISTANCE }.entries[0].displayName)
    }

    private fun entry(rank: Int, uid: String): LeaderboardEntry =
        LeaderboardEntry(
            rank = rank,
            uid = uid,
            displayName = uid,
            avatarPath = null,
            value = (100 - rank).toDouble(),
            isViewer = false,
        )
}
