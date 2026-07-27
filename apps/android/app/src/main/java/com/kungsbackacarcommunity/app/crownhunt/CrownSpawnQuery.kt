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
 * `in` accepts at most [FIRESTORE_IN_LIMIT] values, so the covered area is
 * bounded no matter how far the user zooms out. [MAX_RING] caps it lower still,
 * at a 5x5 block — roughly 1.2 km east-west and 2.2 km north-south at Swedish
 * latitudes.
 *
 * That is a deliberate product choice, not only an index workaround. A crown is
 * collectable from 75 m while parked; a crown 40 km away is not something you
 * are going to drive to, and drawing every crown in the country would (a) make
 * the layer a national scrape of the spawn table for anyone with a wide zoom and
 * (b) cost a document read per crown per pan. Zoomed right out, the layer
 * therefore covers the middle of the screen and not the edges — the honest
 * trade, stated here so an empty corner reads as designed rather than broken.
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
     * Firestore's hard limit on the number of values in an `in` filter. The plan
     * can never exceed this; [MAX_RING] keeps it comfortably below.
     */
    const val FIRESTORE_IN_LIMIT: Int = 30

    /**
     * Largest ring of cells around the centre one. 2 gives a 5x5 = 25-key query,
     * inside [FIRESTORE_IN_LIMIT] with headroom.
     */
    const val MAX_RING: Int = 2

    /**
     * Smallest ring. Never 0: a crown 30 m the other side of a cell boundary is
     * as collectable as one in your own cell, so the neighbours are always
     * included and a crown never vanishes because of an invisible grid line.
     */
    const val MIN_RING: Int = 1

    /**
     * Cap on how much of a wide viewport the layer tries to cover, in metres.
     *
     * Ring selection is driven by the SMALLER of the visible radius and this, so
     * zooming out past it stops widening the query instead of silently clamping
     * at [MAX_RING] with no stated intent.
     */
    const val MAX_QUERY_RADIUS_METERS: Double = 2_500.0

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
        // Belt and braces: the ring cap already keeps this inside the limit, but
        // a future retune of MAX_RING must fail visibly in a test rather than at
        // runtime with an opaque Firestore rejection.
        return if (keys.size > FIRESTORE_IN_LIMIT) keys.subList(0, FIRESTORE_IN_LIMIT) else keys
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
