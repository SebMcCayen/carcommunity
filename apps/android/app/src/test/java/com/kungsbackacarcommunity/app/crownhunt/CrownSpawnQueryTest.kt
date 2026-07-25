package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.incidents.ViewportRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crown layer's query plan: which grid cells a given viewport asks for.
 *
 * The plan is what decides both what the user SEES (a crown missing from the
 * layer is indistinguishable from no crown being there) and what the layer
 * COSTS (a cell is a document read per crown in it, per pan). Both are pinned
 * here rather than inferred from an empty map.
 */
class CrownSpawnQueryTest {

    /** Kungsbacka, roughly. */
    private val lat = 57.4870
    private val lon = 12.0760

    @Test
    fun `the cell grid mirrors the backend's`() {
        // CROWN_CELL_DEGREES in functions/src/crownHunt/crown-spawn-core.ts. A
        // drift here would query cells the spawner never writes to — a silently
        // empty layer, with no error anywhere.
        assertEquals(0.01, CrownSpawnQuery.CELL_DEGREES, 0.0)
        assertEquals("5748_1207", CrownSpawnQuery.cellKey(lat, lon))
    }

    /** Negative coordinates floor DOWNWARD, not toward zero. */
    @Test
    fun `cell keys floor downward in both hemispheres`() {
        assertEquals("-1_-1", CrownSpawnQuery.cellKey(-0.005, -0.005))
        assertEquals("0_0", CrownSpawnQuery.cellKey(0.005, 0.005))
    }

    /**
     * The neighbours are ALWAYS included, even at the tightest zoom.
     *
     * A crown 30 m the other side of a cell boundary is exactly as collectable as
     * one in your own cell, so a plan of "just my cell" would make crowns vanish
     * at an invisible grid line — the kind of bug that reads as the feature being
     * broken rather than as a query being wrong.
     */
    @Test
    fun `even the tightest viewport asks for the surrounding ring`() {
        assertEquals(CrownSpawnQuery.MIN_RING, CrownSpawnQuery.ringsFor(1.0))
        assertEquals(9, CrownSpawnQuery.cellKeysFor(lat, lon, 1.0).size)
        assertTrue(CrownSpawnQuery.cellKeysFor(lat, lon, 1.0).contains("5748_1207"))
    }

    /** A viewport with no measurable radius still shows the crowns right around you. */
    @Test
    fun `an unknown radius falls back to the immediate ring rather than to nothing`() {
        for (unknown in listOf(null, Double.NaN, 0.0, -1.0)) {
            assertEquals("radius=$unknown", CrownSpawnQuery.MIN_RING, CrownSpawnQuery.ringsFor(unknown))
            assertEquals("radius=$unknown", 9, CrownSpawnQuery.cellKeysFor(lat, lon, unknown).size)
        }
    }

    /**
     * Zooming out widens the plan up to a cap, and no further.
     *
     * That cap is a product decision as much as an index workaround: a crown is
     * collectable from 75 m while parked, so drawing every crown in the country
     * would be a national scrape of the spawn table for anyone with a wide zoom,
     * at a document read per crown per pan.
     */
    @Test
    fun `the plan widens with the viewport and then stops`() {
        assertEquals(1, CrownSpawnQuery.ringsFor(500.0))
        assertEquals(2, CrownSpawnQuery.ringsFor(2_000.0))
        assertEquals(CrownSpawnQuery.MAX_RING, CrownSpawnQuery.ringsFor(50_000.0))
        assertEquals(25, CrownSpawnQuery.cellKeysFor(lat, lon, 50_000.0).size)
    }

    /** The plan can never exceed Firestore's `in` limit, however MAX_RING is retuned. */
    @Test
    fun `the plan always fits inside Firestore's in-filter limit`() {
        for (radius in listOf(1.0, 500.0, 2_500.0, 20_000.0, 500_000.0)) {
            val keys = CrownSpawnQuery.cellKeysFor(lat, lon, radius)
            assertTrue(
                "radius=$radius produced ${keys.size} keys",
                keys.size <= CrownSpawnQuery.FIRESTORE_IN_LIMIT,
            )
        }
    }

    /**
     * A NaN centre produces NO plan rather than the key for (0,0) — which would
     * quietly draw crowns off the Gulf of Guinea and query a cell that has
     * nothing to do with the user.
     */
    @Test
    fun `a broken centre produces no query rather than a wrong one`() {
        assertTrue(CrownSpawnQuery.cellKeysFor(Double.NaN, lon, 1_000.0).isEmpty())
        assertTrue(CrownSpawnQuery.cellKeysFor(lat, Double.NaN, 1_000.0).isEmpty())
    }

    /** Two identical inputs produce an identical plan, in an identical order. */
    @Test
    fun `the plan is deterministic`() {
        assertEquals(
            CrownSpawnQuery.cellKeysFor(lat, lon, 1_500.0),
            CrownSpawnQuery.cellKeysFor(lat, lon, 1_500.0),
        )
    }

    // ---- Re-query decision -------------------------------------------------

    @Test
    fun `a settle covering the same cells is not worth a read`() {
        val keys = CrownSpawnQuery.cellKeysFor(lat, lon, 1_000.0)
        // Never queried yet — always worth the first read.
        assertTrue(CrownSpawnQuery.shouldRequery(null, keys))
        // Same cells, different order: the same rows, so no read.
        assertFalse(CrownSpawnQuery.shouldRequery(keys.reversed(), keys))
        // A genuinely different area.
        val moved = CrownSpawnQuery.cellKeysFor(lat + 0.5, lon, 1_000.0)
        assertTrue(CrownSpawnQuery.shouldRequery(keys, moved))
    }

    /** An empty plan is never worth a read — there is nothing to ask for. */
    @Test
    fun `an empty plan is never re-queried`() {
        assertFalse(CrownSpawnQuery.shouldRequery(listOf("1_1"), emptyList()))
    }

    /**
     * Distance is the same haversine the rest of the app uses, so a distance
     * shown next to a crown and one computed anywhere else agree.
     */
    @Test
    fun `distance agrees with the shared viewport geometry`() {
        val d = CrownSpawnQuery.distanceMeters(lat, lon, lat + 0.01, lon + 0.01)
        assertEquals(ViewportRadius.haversineMeters(lat, lon, lat + 0.01, lon + 0.01), d, 0.0)
        // Sanity: 0.01 degrees of latitude is a bit over a kilometre.
        assertTrue("expected roughly 1.3 km, got $d", d in 1_000.0..1_800.0)
    }
}
