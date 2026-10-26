package com.kungsbackacarcommunity.app.crownhunt

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownPointCollectionPresentationTest {
    private val now = Instant.parse("2026-10-26T22:59:59Z").toEpochMilli()
    private val nextUtcDay = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli()
    private val point = CrownHuntPoint("admin", "Torg", null, 50, 57.48, 12.07, 40.0)

    private fun done(result: CrownHuntClaimResult) = CrownHuntClaimStatus.Done(ClaimOutcome(result, 7, 107))

    @Test
    fun onlyAwardedSuppressesPointAndLeavesOutcomeAndPointReadable() {
        val outcome = done(CrownHuntClaimResult.AWARDED)
        val suppressed = CrownPointCollectionPresentation.afterClaim(emptyMap(), point.id, outcome, now)
        assertEquals(mapOf(point.id to nextUtcDay), suppressed)
        assertTrue(CrownPointMarkers.markers(listOf(point), true, 42, suppressedIds = suppressed.keys).isEmpty())
        assertEquals(CrownHuntClaimResult.AWARDED, outcome.outcome.result)
        assertEquals(7, outcome.outcome.pointsAwarded)
        assertEquals("Torg", point.title)
    }

    @Test
    fun allRejectionsErrorsAndPendingStatesLeavePresentationUntouched() {
        val existing = mapOf("other" to nextUtcDay)
        val statuses = listOf(
            CrownHuntClaimStatus.Idle,
            CrownHuntClaimStatus.Claiming,
            CrownHuntClaimStatus.NeedsLocation,
            CrownHuntClaimStatus.Failed,
        ) + (CrownHuntClaimResult.entries - CrownHuntClaimResult.AWARDED).map { done(it) }
        statuses.forEach { status ->
            val suppressed = CrownPointCollectionPresentation.afterClaim(existing, point.id, status, now)
            assertSame(existing, suppressed)
            assertEquals(listOf(point.id), CrownPointMarkers.markers(
                listOf(point), true, 42, suppressedIds = suppressed.keys,
            ).map { it.id })
        }
    }

    @Test
    fun suppressionExpiresAtUtcMidnightIncludingWhileMapStaysOpen() {
        val suppressed = CrownPointCollectionPresentation.afterClaim(
            emptyMap(), point.id, done(CrownHuntClaimResult.AWARDED), now,
        )
        // Still suppressed at Stockholm local midnight (23:00 UTC in October).
        assertEquals(suppressed, CrownPointCollectionPresentation.prune(suppressed, now + 1_000))
        assertEquals(suppressed, CrownPointCollectionPresentation.prune(suppressed, nextUtcDay - 1))
        val expired = CrownPointCollectionPresentation.prune(suppressed, nextUtcDay)
        assertTrue(expired.isEmpty())
        assertEquals(listOf(point.id), CrownPointMarkers.markers(
            listOf(point), true, 42, suppressedIds = expired.keys,
        ).map { it.id })
        val newAward = CrownPointCollectionPresentation.afterClaim(
            expired, point.id, done(CrownHuntClaimResult.AWARDED), nextUtcDay,
        )
        assertEquals(nextUtcDay + 86_400_000L, newAward.getValue(point.id))
    }

    @Test
    fun successfulAwardsPruneOldEntriesAndCapRecentSuppression() {
        var suppressed = mapOf("expired" to now)
        repeat(CrownPointCollectionPresentation.MAX_ENTRIES + 2) { index ->
            suppressed = CrownPointCollectionPresentation.afterClaim(
                suppressed, "p$index", done(CrownHuntClaimResult.AWARDED), now,
            )
        }
        assertEquals(CrownPointCollectionPresentation.MAX_ENTRIES, suppressed.size)
        assertFalse(suppressed.containsKey("expired"))
        assertFalse(suppressed.containsKey("p0"))
        assertFalse(suppressed.containsKey("p1"))
        assertTrue(suppressed.containsKey("p201"))
    }

    @Test
    fun suppressionOnlyAffectsMatchingAdminPointsAndPreservesMetadataAndNoFixSlate() {
        val points = listOf(point, point.copy(id = "other"))
        val markers = CrownPointMarkers.markers(points, true, 42, suppressedIds = setOf(point.id))
        val marker = markers.single()
        assertEquals("other", marker.id)
        assertEquals(50, marker.rewardPoints)
        assertEquals(null, marker.rarity)
        assertEquals(CrownMarkerStyle.OUT_OF_RANGE_DISC, marker.discColorArgb)
        assertEquals(42, marker.iconRes)
        assertEquals(2, points.size)
        // A different uid / fresh composition starts with no admin suppression.
        assertEquals(2, CrownPointMarkers.markers(points, true, 42).size)
    }
}
