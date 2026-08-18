package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.BadgeTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Kronjakt page's two data decisions:
 *  1. empty-vs-loaded — the bug the empty state fixes;
 *  2. the member's Kronjägare standing derived from their award documents,
 *     which must NEVER carry a crowns-collected count (backend-only).
 */
class CrownHuntStatsTest {

    private fun badge(key: String) = Badge(key = key, fallbackName = null, awardedAtMillis = 1L)

    // --- empty-vs-loaded page decision -------------------------------------

    @Test
    fun `loaded with no points is the empty case`() {
        val state = CrownHuntPointsState.Loaded(emptyList())
        assertTrue((state as CrownHuntPointsState.Loaded).points.isEmpty())
    }

    @Test
    fun `loaded with a point is not the empty case`() {
        val point =
            CrownHuntPoint(
                id = "p1",
                title = "Torg-kronan",
                description = null,
                rewardPoints = 50,
                latitude = null,
                longitude = null,
                geofenceRadiusMeters = null,
            )
        val state = CrownHuntPointsState.Loaded(listOf(point))
        assertFalse((state as CrownHuntPointsState.Loaded).points.isEmpty())
    }

    // --- Kronjägare standing -----------------------------------------------

    @Test
    fun `no badges yet offers the first rung and no held tier`() {
        val standing = CrownHuntStats.kronjagare(emptyList())
        assertNull(standing.highestTier)
        assertTrue(standing.isUnstarted)
        assertEquals(BadgeTier.BRONS, standing.nextTier)
        // First Kronjägare rung is 10 crowns.
        assertEquals(10L, standing.nextThresholdCrowns)
        assertFalse(standing.isComplete)
    }

    @Test
    fun `holding silver reports silver held and guld at 250 next`() {
        val standing =
            CrownHuntStats.kronjagare(
                listOf(badge("kronjagare_brons"), badge("kronjagare_silver")),
            )
        assertEquals(BadgeTier.SILVER, standing.highestTier)
        assertEquals(BadgeTier.GULD, standing.nextTier)
        assertEquals(250L, standing.nextThresholdCrowns)
        assertFalse(standing.isUnstarted)
        assertFalse(standing.isComplete)
    }

    @Test
    fun `holding platina is complete with no next goal`() {
        val standing =
            CrownHuntStats.kronjagare(
                listOf(
                    badge("kronjagare_brons"),
                    badge("kronjagare_silver"),
                    badge("kronjagare_guld"),
                    badge("kronjagare_platina"),
                ),
            )
        assertEquals(BadgeTier.PLATINA, standing.highestTier)
        assertNull(standing.nextTier)
        assertNull(standing.nextThresholdCrowns)
        assertTrue(standing.isComplete)
    }

    @Test
    fun `unrelated badges do not affect the kronjagare standing`() {
        val standing =
            CrownHuntStats.kronjagare(
                listOf(badge("vagfarare_guld"), badge("samlare_brons"), badge("first_event")),
            )
        assertNull(standing.highestTier)
        assertTrue(standing.isUnstarted)
        assertEquals(BadgeTier.BRONS, standing.nextTier)
    }

    // --- crowns-toward-next progress (the rank-text figure) ----------------

    @Test
    fun `count unknown yields no progress figure`() {
        val standing = CrownHuntStats.kronjagare(emptyList(), crownsCollected = null)
        assertNull(standing.crownsCollected)
        assertNull(standing.crownsTowardNext)
    }

    @Test
    fun `nine crowns and no rung reports 9 of 10 toward Brons`() {
        // The reported case: the member has collected crowns but not yet reached
        // the first rung, so the copy must show progress, not "your first crown".
        val standing = CrownHuntStats.kronjagare(emptyList(), crownsCollected = 9L)
        assertTrue(standing.isUnstarted)
        assertEquals(BadgeTier.BRONS, standing.nextTier)
        assertEquals(10L, standing.nextThresholdCrowns)
        assertEquals(9L, standing.crownsCollected)
        assertEquals(9L, standing.crownsTowardNext)
    }

    @Test
    fun `count at or above the next threshold clamps to the goal line`() {
        // The badge award trails the counter by a trigger/sweep tick, so the count
        // can briefly exceed the still-unearned rung — it must read "10 / 10", not
        // "12 / 10", until the award lands and the next rung takes over.
        val standing = CrownHuntStats.kronjagare(emptyList(), crownsCollected = 12L)
        assertEquals(10L, standing.nextThresholdCrowns)
        assertEquals(10L, standing.crownsTowardNext)
    }

    @Test
    fun `progress is measured against the next unheld rung, not the first`() {
        val standing =
            CrownHuntStats.kronjagare(
                listOf(badge("kronjagare_brons"), badge("kronjagare_silver")),
                crownsCollected = 60L,
            )
        assertEquals(BadgeTier.GULD, standing.nextTier)
        assertEquals(250L, standing.nextThresholdCrowns)
        assertEquals(60L, standing.crownsTowardNext)
    }

    @Test
    fun `platina held has no progress figure even with a count`() {
        val standing =
            CrownHuntStats.kronjagare(
                listOf(
                    badge("kronjagare_brons"),
                    badge("kronjagare_silver"),
                    badge("kronjagare_guld"),
                    badge("kronjagare_platina"),
                ),
                crownsCollected = 1_500L,
            )
        assertTrue(standing.isComplete)
        assertNull(standing.crownsTowardNext)
    }

    @Test
    fun `a negative or zero count never renders below zero`() {
        assertEquals(
            0L,
            CrownHuntStats.kronjagare(emptyList(), crownsCollected = 0L).crownsTowardNext,
        )
        // A corrupt/negative counter must clamp UP to the floor, not render "-3 / 10".
        assertEquals(
            0L,
            CrownHuntStats.kronjagare(emptyList(), crownsCollected = -3L).crownsTowardNext,
        )
    }

    @Test
    fun `a gap left by a partial write still offers the lowest unheld rung`() {
        // Monotonic backend never does this, but the fold must be robust: holding
        // guld without silver still reports the highest held and the lowest unheld.
        val standing =
            CrownHuntStats.kronjagare(
                listOf(badge("kronjagare_brons"), badge("kronjagare_guld")),
            )
        assertEquals(BadgeTier.GULD, standing.highestTier)
        assertEquals(BadgeTier.SILVER, standing.nextTier)
        assertEquals(50L, standing.nextThresholdCrowns)
    }
}
