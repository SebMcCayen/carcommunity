package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.incidents.ViewportRadius
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Turns "where is the map looking?" into "which `crownSpawns` cells do I ask
 * for?" — the crown layer's equivalent of the incident layer's
 * [com.kungsbackacarcommunity.app.incidents.ViewportRadius].
 *
 * ## Why cells and not a radius
 *
 * The incident layer calls a callable that takes a centre + a radius. Crowns are
 * read STRAIGHT FROM FIRESTORE (the security rule already narrows the collection
 * to live, unexpired crowns for an active member), and the deployed composite
 * index is `cellKey ASC, status ASC, expiresAt ASC`. Firestore cannot do
 * "within N metres", so the query is `cellKey in [...]` and the geography comes
 * from the grid. The centre/idle/keep-alive machinery around it is deliberately
 * unchanged — see [CrownSpawnController].
 *
 * ## Why the coverage is capped, and what that costs
 *
 * `in` accepts at most [FIRESTORE_IN_LIMIT] values, so a single query is bounded
 * — but a town-sized view needs more cells than that, so the plan emits the FULL
 * (bounded) key list and the repository splits it into BATCHES of at most
 * [FIRESTORE_IN_LIMIT] keys, one independent `in` query each, merged by crown id.
 * Two hard caps keep that from becoming an unbounded scrape however far the user
 * zooms out: [MAX_RING] fixes the widest block at 11x11 = 121 cells — roughly
 * 3 km east-west and 5.5 km north-south each way from centre at Swedish latitudes
 * — and [MAX_CELLS] / [MAX_BATCHES] cap the plan at 150 cells across at most 5
 * batches even if [MAX_RING] is later retuned upward.
 *
 * That ceiling is a deliberate product choice, not only an index workaround. A
 * crown is collectable from 75 m while parked; a crown 40 km away is not
 * something you are going to drive to, and drawing every crown in the country
 * would (a) make the layer a national scrape of the spawn table for anyone with a
 * wide zoom and (b) cost a document read per crown per pan. The widened plan
 * covers a town, not a county: zoomed right out past that, the layer covers the
 * middle of the screen and not the far edges — the honest trade, stated here so
 * an empty corner reads as designed rather than broken.
 *
 * ## What a refresh costs
 *
 * Each batch is a separate Firestore query, so a full-width plan is at most
 * [MAX_BATCHES] round-trips (run in parallel). N live crowns in range still cost
 * N document reads regardless of how the cells are batched; the total is bounded
 * by [MAX_CELLS] cells and, on the draw side, by
 * [CrownSpawnRepository.MAX_SPAWNS_PER_QUERY].
 *
 * Pure Kotlin, so the whole plan is unit-tested rather than inferred from an
 * empty map.
 */
object CrownSpawnQuery {
    /**
     * Spawn-grid cell edge in degrees, mirroring `CROWN_CELL_DEGREES` in
     * `functions/src/crownHunt/crown-spawn-core.ts`. ~1.1 km of latitude;
     * ~600 m of longitude at 57.5 degrees north.
     */
    const val CELL_DEGREES: Double = 0.01

    /**
     * Firestore's hard limit on the number of values in an `in` filter, and so
     * the size of one BATCH. A full-width plan exceeds this, so the repository
     * chunks [cellKeysFor] into batches of at most this many keys — see
     * [chunkForInQueries] — and runs one `in` query per batch.
     */
    const val FIRESTORE_IN_LIMIT: Int = 30

    /**
     * Largest ring of cells around the centre one. 5 gives an 11x11 = 121-key
     * plan — a town-sized area — split across at most [MAX_BATCHES] `in` queries.
     * Sits inside the [MAX_CELLS] hard cap with headroom.
     */
    const val MAX_RING: Int = 5

    /**
     * Smallest ring. Never 0: a crown 30 m the other side of a cell boundary is
     * as collectable as one in your own cell, so the neighbours are always
     * included and a crown never vanishes because of an invisible grid line.
     */
    const val MIN_RING: Int = 1

    /**
     * Hard cap on the number of cells one plan may contain, independent of
     * [MAX_RING]. At 5 crowns/cell (the spawner's density budget) this bounds a
     * single refresh's reads, and it means a future bump to [MAX_RING] fails
     * visibly in a test rather than silently scraping a county. 150 cells is
     * exactly [MAX_BATCHES] x [FIRESTORE_IN_LIMIT].
     */
    const val MAX_CELLS: Int = 150

    /**
     * Hard cap on how many `in` queries one refresh may fan out into. With
     * [FIRESTORE_IN_LIMIT] keys each, [MAX_BATCHES] x [FIRESTORE_IN_LIMIT] is the
     * [MAX_CELLS] ceiling; the 11x11 = 121-cell full-width plan uses all 5.
     */
    const val MAX_BATCHES: Int = 5

    /**
     * Cap on how much of a wide viewport the layer tries to cover, in metres.
     *
     * Ring selection is driven by the SMALLER of the visible radius and this, so
     * zooming out past it stops widening the query instead of silently clamping
     * at [MAX_RING] with no stated intent. Widened to a town-sized ~9 km so the
     * corners of a zoomed-out town view are no longer empty; the actual reach is
     * then bounded by [MAX_RING] (the ~5.5 km-north/south block).
     */
    const val MAX_QUERY_RADIUS_METERS: Double = 9_000.0

    private const val METERS_PER_DEGREE_LAT: Double = 111_320.0

    /**
     * Deterministic grid key for a coordinate — `${latIdx}_${lonIdx}`.
     *
     * BOTH axes are clamped before flooring, exactly as the backend's
     * `crownCellKey` in `functions/src/crownHunt/crown-spawn-core.ts` does. This
     * has to match key-for-key, not just approximately: the spawner writes the
     * key the backend computes and this is the key we query on, so a client that
     * clamped only latitude would ask for `18100_...`-style cells that the
     * spawner never writes — a silently EMPTY layer rather than a visible error.
     * Longitude outside [-180, 180] is not hypothetical: the map wraps the world,
     * so panning across the anti-meridian hands us 180.1 and beyond.
     */
    fun cellKey(latitude: Double, longitude: Double): String {
        val latIdx = floor(clampLat(latitude) / CELL_DEGREES).toInt()
        val lonIdx = floor(clampLon(longitude) / CELL_DEGREES).toInt()
        return "${latIdx}_$lonIdx"
    }

    /** Latitude clamp mirroring the backend's `clampLat`. */
    private fun clampLat(latitude: Double): Double = latitude.coerceIn(-90.0, 90.0)

    /** Longitude clamp mirroring the backend's `clampLon`. */
    private fun clampLon(longitude: Double): Double = longitude.coerceIn(-180.0, 180.0)

    /**
     * How many rings of cells to ask for, given the map's visible radius.
     *
     * A null / non-finite / non-positive radius (no camera yet, a stub surface,
     * a degenerate projection) falls back to [MIN_RING] rather than to nothing:
     * the honest answer to "I don't know how far you can see" is "show the
     * crowns right around you", not "show none".
     */
    fun ringsFor(visibleRadiusMeters: Double?): Int {
        if (visibleRadiusMeters == null || !visibleRadiusMeters.isFinite()) return MIN_RING
        if (visibleRadiusMeters <= 0.0) return MIN_RING
        val effective = min(visibleRadiusMeters, MAX_QUERY_RADIUS_METERS)
        // Latitude is the tighter axis in metres-per-cell terms only at the
        // equator; using it everywhere means the ring count is never LARGER than
        // the visible area justifies, which is the direction that matters for
        // read cost.
        val cellMeters = CELL_DEGREES * METERS_PER_DEGREE_LAT
        val rings = ceil(effective / cellMeters).toInt()
        return rings.coerceIn(MIN_RING, MAX_RING)
    }

    /**
     * The cell keys covering the viewport centred on [centerLat]/[centerLon].
     *
     * Ordered stably (south-to-north, west-to-east) so two calls with the same
     * inputs produce an identical query — a set whose order wobbled would defeat
     * any downstream de-duplication and make the tests non-deterministic.
     *
     * Returns an empty list for a non-finite centre rather than fabricating a
     * key: a NaN coordinate is a bug upstream, and querying `"0_0"` for it would
     * quietly draw crowns off the Gulf of Guinea.
     */
    fun cellKeysFor(
        centerLat: Double,
        centerLon: Double,
        visibleRadiusMeters: Double?,
    ): List<String> {
        if (!centerLat.isFinite() || !centerLon.isFinite()) return emptyList()
        val rings = ringsFor(visibleRadiusMeters)
        // Same clamp on both axes as [cellKey] / the backend's `crownCellKey`,
        // so the centre of the query plan is a cell the spawner can actually
        // have written. See [cellKey] for why an unclamped longitude reads as an
        // empty layer instead of an error.
        val latIdx = floor(clampLat(centerLat) / CELL_DEGREES).toInt()
        val lonIdx = floor(clampLon(centerLon) / CELL_DEGREES).toInt()
        val keys = ArrayList<String>((2 * rings + 1) * (2 * rings + 1))
        for (dLat in -rings..rings) {
            for (dLon in -rings..rings) {
                keys.add("${latIdx + dLat}_${lonIdx + dLon}")
            }
        }
        // Belt and braces: the ring cap already keeps this inside MAX_CELLS, but
        // a future retune of MAX_RING must fail visibly (a truncated plan a test
        // can catch) rather than fan out into an unbounded number of batches.
        return if (keys.size > MAX_CELLS) keys.subList(0, MAX_CELLS) else keys
    }

    /**
     * Splits a plan into batches of at most [FIRESTORE_IN_LIMIT] keys, each a
     * legal `in` query the repository runs independently and merges.
     *
     * The [MAX_CELLS] cap is enforced HERE, not merely inherited from
     * [cellKeysFor]: the input is truncated to [MAX_CELLS] keys before chunking,
     * so the result is always at most [MAX_BATCHES] batches no matter what a
     * caller passes. That makes the fan-out bound a property of this function
     * rather than of the one caller that happens to feed it a bounded plan — a
     * future caller cannot trigger an unbounded number of parallel queries.
     * Order is preserved so the same plan always chunks the same way; an empty
     * plan yields no batches (nothing to ask for), never a single empty `in`
     * array, which Firestore rejects.
     */
    fun chunkForInQueries(cellKeys: List<String>): List<List<String>> {
        if (cellKeys.isEmpty()) return emptyList()
        val bounded = if (cellKeys.size > MAX_CELLS) cellKeys.subList(0, MAX_CELLS) else cellKeys
        return bounded.chunked(FIRESTORE_IN_LIMIT)
    }

    /**
     * Whether a settled camera is worth re-querying, given where we last did.
     *
     * Reuses the incident layer's rule shape but compares CELL SETS rather than
     * distances: the query's granularity IS the grid, so a pan that lands inside
     * the same cells would return byte-identical results and is not worth a
     * read. This is what keeps a nudge-y pan from costing 25 document reads.
     */
    fun shouldRequery(lastKeys: List<String>?, nextKeys: List<String>): Boolean {
        if (lastKeys == null) return true
        if (nextKeys.isEmpty()) return false
        return lastKeys.toSet() != nextKeys.toSet()
    }

    /**
     * Great-circle distance in metres — the same IUGG mean radius the incident
     * layer uses, so a distance shown next to a crown and a distance computed
     * anywhere else in the app agree.
     *
     * Present here rather than borrowed from `ViewportRadius` only so this file
     * stays readable as one unit; the constant and the formula are identical and
     * a test pins them against each other.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        ViewportRadius.haversineMeters(lat1, lon1, lat2, lon2)
}
