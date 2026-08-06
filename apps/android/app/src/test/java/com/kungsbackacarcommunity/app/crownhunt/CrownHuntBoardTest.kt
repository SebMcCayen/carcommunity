package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PURE stats/leaderboard composition the hub page renders — ranking, board
 * assembly, viewer highlight, and personal-stats folding — from mocked docs. The
 * ranking MUST agree with the backend's `rankLeaderboard` (points desc, crowns
 * desc, uid asc), which is the part a wrong tiebreak would silently corrupt.
 */
class CrownHuntBoardTest {
    private fun counter(uid: String, points: Int, crowns: Int) =
        CrownLeaderboardCounter(uid = uid, points = points, crownsCollected = crowns)

    @Test
    fun ranksByPointsThenCrownsThenUid() {
        val ranked =
            CrownHuntBoard.rank(
                listOf(
                    counter("b", 100, 5),
                    counter("a", 100, 5), // ties b on points+crowns → uid breaks it
                    counter("c", 300, 1),
                    counter("d", 100, 9), // more crowns than a/b at same points
                ),
            )
        // c (300) first; then d (100/9); then a & b (100/5) with uid asc: a before b.
        assertEquals(listOf("c", "d", "a", "b"), ranked.map { it.first.uid })
        assertEquals(listOf(1, 2, 3, 4), ranked.map { it.second })
    }

    @Test
    fun boardResolvesNamesFlagsViewerAndSurfacesViewerRank() {
        val board =
            CrownHuntBoard.board(
                counters = listOf(counter("alice", 500, 12), counter("me", 300, 7)),
                viewerUid = "me",
                names = mapOf("alice" to "Alice", "me" to "You"),
                seasonId = "2026-08",
            )
        assertEquals("2026-08", board.seasonId)
        assertEquals(listOf("Alice", "You"), board.rows.map { it.displayName })
        assertEquals(2, board.viewerRank)
        assertTrue(board.rows.first { it.uid == "me" }.isViewer)
        assertFalse(board.rows.first { it.uid == "alice" }.isViewer)
    }

    @Test
    fun missingNameFallsBackToShortUidStubNotBlank() {
        val board =
            CrownHuntBoard.board(
                counters = listOf(counter("abcdefghij", 10, 1)),
                viewerUid = null,
                names = emptyMap(),
                seasonId = "2026-08",
            )
        assertEquals("abcdefgh", board.rows.single().displayName)
    }

    @Test
    fun viewerRankNullWhenViewerNotInFetchedPage() {
        val board =
            CrownHuntBoard.board(
                counters = listOf(counter("alice", 500, 12)),
                viewerUid = "me",
                names = emptyMap(),
                seasonId = "2026-08",
            )
        assertNull(board.viewerRank)
    }

    @Test
    fun personalStatsFoldsCountersAndRichDoc() {
        val stats =
            CrownHuntBoard.personalStats(
                allTime = counter("me", 1200, 40),
                season = counter("me", 300, 7),
                seasonRank = 2,
                rich =
                    CrownUserStatsDoc(
                        byRarity = mapOf(CrownRarity.COMMON to 30, CrownRarity.RARE to 10),
                        streakCurrent = 4,
                        streakBest = 9,
                        seasonsWon = 1,
                        rarest = CrownRarity.RARE,
                    ),
            )
        requireNotNull(stats)
        assertEquals(1200, stats.points)
        assertEquals(40, stats.crownsCollected)
        assertEquals(2, stats.seasonRank)
        assertEquals(300, stats.seasonPoints)
        assertEquals(4, stats.streakCurrent)
        assertEquals(1, stats.seasonsWon)
        assertEquals(CrownRarity.RARE, stats.rarest)
        assertEquals(10, stats.byRarity[CrownRarity.RARE])
    }

    @Test
    fun personalStatsIsNullWithNothingToShowYet() {
        assertNull(
            CrownHuntBoard.personalStats(allTime = null, season = null, seasonRank = null, rich = null),
        )
    }

    @Test
    fun personalStatsDefaultsZerosWhenOnlyRichDocExists() {
        // A member whose leaderboard entry has not landed yet but who has a stats
        // doc (streak/rarity) still renders — zeros for the counters, no crash.
        val stats =
            CrownHuntBoard.personalStats(
                allTime = null,
                season = null,
                seasonRank = null,
                rich =
                    CrownUserStatsDoc(
                        byRarity = emptyMap(),
                        streakCurrent = 1,
                        streakBest = 1,
                        seasonsWon = 0,
                        rarest = null,
                    ),
            )
        requireNotNull(stats)
        assertEquals(0, stats.points)
        assertEquals(0, stats.crownsCollected)
        assertNull(stats.seasonRank)
        assertNull(stats.rarest)
    }
}
