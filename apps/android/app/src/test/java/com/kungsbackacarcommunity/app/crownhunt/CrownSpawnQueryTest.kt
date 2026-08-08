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
     * BOTH axes are clamped before flooring, matching the backend's `clampLat` /
     * `clampLon` in `functions/src/crownHunt/crown-spawn-core.ts`.
     *
     * The map wraps the world, so panning east past the anti-meridian hands us a
     * longitude of 180.1 and up. Flooring that unclamped yields `18010`, a cell
     * index the spawner can never write — so the layer would go quietly empty
     * out there rather than fail. Clamping to the same bounds as the writer is
     * what keeps "no crowns drawn" meaning "no crowns there".
     */
    @Test
    fun `both axes are clamped to the globe, exactly as the backend clamps them`() {
        // 180.1 and 181 both clamp to 180 → lonIdx 18000, the backend's own
        // maximum longitude index.
        assertEquals("5748_18000", CrownSpawnQuery.cellKey(lat, 180.1))
        assertEquals("5748_18000", CrownSpawnQuery.cellKey(lat, 181.0))
        assertEquals("5748_-18000", CrownSpawnQuery.cellKey(lat, -180.5))
        // Latitude was already clamped; pinned here so the pair stays symmetric.
        assertEquals("9000_1207", CrownSpawnQuery.cellKey(95.0, lon))
        assertEquals("-9000_1207", CrownSpawnQuery.cellKey(-95.0, lon))
    }

    /**
     * The PLAN's centre is clamped the same way, not only the single-cell key —
     * they have to agree or a crown drawn from the plan could not be looked up.
     */
    @Test
    fun `the query plan clamps its centre on both axes too`() {
        assertEquals(
            CrownSpawnQuery.cellKeysFor(lat, 180.0, 1.0),
            CrownSpawnQuery.cellKeysFor(lat, 181.0, 1.0),
        )
        assertTrue(CrownSpawnQuery.cellKeysFor(lat, 181.0, 1.0).contains("5748_18000"))
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
        // MAX_RING = 5 → an 11x11 = 121-cell town-sized block, and no wider.
        assertEquals(121, CrownSpawnQuery.cellKeysFor(lat, lon, 50_000.0).size)
    }

    /**
     * A ~9 km town-sized radius reaches the full ring, not just the near block —
     * the whole point of the widen. It is bounded there by MAX_RING, not the
     * visible radius, so an even wider zoom adds no more cells.
     */
    @Test
    fun `a town-sized radius reaches the full ring and stops`() {
        assertEquals(CrownSpawnQuery.MAX_RING, CrownSpawnQuery.ringsFor(9_000.0))
        assertEquals(
            CrownSpawnQuery.cellKeysFor(lat, lon, 9_000.0),
            CrownSpawnQuery.cellKeysFor(lat, lon, 40_000.0),
        )
    }

    /**
     * The plan can never exceed the hard cell cap, however MAX_RING is retuned,
     * and it always splits into legal `in` batches (<= the 30-value limit) that
     * are themselves bounded in number.
     */
    @Test
    fun `the plan is bounded and batches into legal in-queries`() {
        for (radius in listOf(1.0, 500.0, 2_500.0, 9_000.0, 20_000.0, 500_000.0)) {
            val keys = CrownSpawnQuery.cellKeysFor(lat, lon, radius)
            assertTrue(
                "radius=$radius produced ${keys.size} keys",
                keys.size <= CrownSpawnQuery.MAX_CELLS,
            )
            val batches = CrownSpawnQuery.chunkForInQueries(keys)
            assertTrue(
                "radius=$radius produced ${batches.size} batches",
                batches.size <= CrownSpawnQuery.MAX_BATCHES,
            )
            for (batch in batches) {
                assertTrue(
                    "radius=$radius produced a ${batch.size}-key batch",
                    batch.isNotEmpty() && batch.size <= CrownSpawnQuery.FIRESTORE_IN_LIMIT,
                )
            }
            // The batches partition the plan exactly — no key dropped, none added.
            assertEquals(keys, batches.flatten())
        }
    }

    /**
     * The full-width plan uses every batch and every key, and the near plan stays
     * a single small batch — the widen must not cost small-zoom refreshes a
     * needless second round-trip.
     */
    @Test
    fun `batching matches the plan size at both ends`() {
        val wide = CrownSpawnQuery.chunkForInQueries(CrownSpawnQuery.cellKeysFor(lat, lon, 9_000.0))
        assertEquals(CrownSpawnQuery.MAX_BATCHES, wide.size)
        assertEquals(121, wide.sumOf { it.size })

        val near = CrownSpawnQuery.chunkForInQueries(CrownSpawnQuery.cellKeysFor(lat, lon, 1.0))
        assertEquals(1, near.size)
        assertEquals(9, near.first().size)
    }

    /** An empty plan yields no batches — never a single empty `in` array. */
    @Test
    fun `an empty plan chunks into no batches`() {
        assertTrue(CrownSpawnQuery.chunkForInQueries(emptyList()).isEmpty())
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
