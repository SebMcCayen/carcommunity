package com.kungsbackacarcommunity.app.drives

/**
 * A per-kilometre marker along a driven route: the interpolated position where
 * the cumulative driven distance first crossed [kilometer] × 1 km, tagged with
 * that whole-kilometre number (1, 2, 3, …). Pure data (no Android / Mapbox
 * types) so [RouteDistanceMarkers] is fully JVM-unit-testable.
 */
data class KmMarker(
    val latitude: Double,
    val longitude: Double,
    val kilometer: Int,
)

/**
 * Computes the "1 km / 2 km / …" markers Seb asked for on the History
 * full-screen route map ("markings for each km that was driven").
 *
 * The saved route stores only ordered [RoutePoint]s and a single total
 * [SavedDrive.distanceMeters] — there is NO per-point running distance — so the
 * marker positions are derived here client-side: walk consecutive points
 * accumulating great-circle (Haversine) distance, and every time the running
 * total first reaches an integer kilometre, interpolate the exact position along
 * the segment where that crossing happens.
 *
 * ## Same distance model as the drive's stored total
 * The per-segment distance comes from [DriveSummary.segmentDistanceMetres], the
 * exact filter the recorder, the in-app summary and the backend use: a segment
 * with a non-positive time delta, or one implying a speed above
 * ~200 km/h ([DriveSummary] `MAX_PLAUSIBLE_SPEED_MPS`), is a GPS jump and
 * contributes 0 — no distance accumulated and no marker placed on it. Reusing
 * that one function (rather than a second Haversine here) is what keeps the km
 * markers from over-counting a glitch and disagreeing with the distance the user
 * sees for the drive.
 *
 * ## Why this is a pure object
 * The GL map (annotations, camera) can only be verified on a token-provisioned
 * device, so the ACTUAL logic — distance accumulation, boundary detection and
 * interpolation — lives here where a JVM unit test can assert marker count and
 * position against a hand-computed synthetic route. The popup map just draws
 * whatever this returns.
 *
 * ## Interval coarsening (bounded annotation count)
 * The spacing adapts to the route length so the popup never has to draw an
 * unbounded number of annotations. Up to [MAX_MARKERS] × 1 km the interval is
 * 1 km (a marker at 1, 2, 3, …); beyond that the interval steps up through
 * [INTERVAL_STEPS_KM] (2, 5, 10, 20, … km) to the smallest value that keeps the
 * total count at or below [MAX_MARKERS]. So a normal drive gets per-km markers,
 * while a 3 000 km road-trip (or a corrupt route claiming to span the globe)
 * gets evenly-spaced coarser markers instead of thousands of overlapping dots.
 * Every marker's [KmMarker.kilometer] is still the true whole-kilometre figure
 * (5, 10, 15, … at a 5 km interval), so the label reads correctly.
 *
 * ## Edge cases (never throws)
 * - Fewer than two points, or a total under 1 km: no markers (empty list).
 * - A zero-length or non-finite segment is skipped so it can neither divide by
 *   zero nor stall the walk.
 * - A single long segment that spans several intervals emits every crossing it
 *   contains (e.g. a 3.4 km straight leg at a 1 km interval yields 1/2/3 km).
 * - Very long / corrupt routes coarsen the interval (above) and are still hard
 *   capped at [MAX_MARKERS] as a final backstop, so drawing can never be handed
 *   an unbounded list.
 */
object RouteDistanceMarkers {
    private const val METERS_PER_KM = 1_000.0

    /**
     * Cap on emitted markers. Sized for what a map can legibly draw (each marker
     * is a dot AND a text label, so this is up to ~1 000 annotations), not for a
     * theoretical route length: 500 markers is already 500 km of per-km marks,
     * far beyond any real drive, and the interval coarsening (see class KDoc)
     * keeps even a globe-spanning corrupt route at or under this figure while
     * still spacing the markers evenly. A hard backstop in the walk enforces it.
     */
    private const val MAX_MARKERS = 500

    /**
     * Whole-kilometre intervals the spacing may step up through as a route grows,
     * chosen so the total marker count stays ≤ [MAX_MARKERS]. Ascending; each
     * keeps markers on "round" kilometre figures (2, 5, 10, 20, … km).
     */
    private val INTERVAL_STEPS_KM = intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000)

    /**
     * Returns the ordered per-kilometre markers for [points], or an empty list
     * when there is nothing to mark (see the class KDoc for every edge case).
     */
    fun markers(points: List<RoutePoint>): List<KmMarker> {
        if (points.size < 2) return emptyList()

        val totalMeters = totalDistanceMeters(points)
        if (totalMeters < METERS_PER_KM) return emptyList()

        // Pick the coarsest-necessary interval so a very long (or corrupt) route
        // stays within the drawable cap while normal drives keep 1 km spacing.
        val intervalMeters = intervalKmFor(totalMeters) * METERS_PER_KM

        val result = ArrayList<KmMarker>()
        var cumulative = 0.0
        // The next distance (metres) at which a marker falls; strides by the
        // interval, so the loop always terminates.
        var nextBoundary = intervalMeters
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            // Same filtered distance the drive's stored total uses: a GPS jump,
            // a non-positive time delta or a duplicate fix contributes 0 and is
            // skipped, so it advances no distance, places no marker, and can't
            // divide the interpolation by zero (see class KDoc).
            val segLen = DriveSummary.segmentDistanceMetres(a, b)
            if (!segLen.isFinite() || segLen <= 0.0) continue

            val segStart = cumulative
            val segEnd = cumulative + segLen
            // Emit one marker per interval boundary that falls inside this segment.
            while (nextBoundary <= segEnd) {
                // Fraction of the way along this segment where the crossing lands.
                val t = ((nextBoundary - segStart) / segLen).coerceIn(0.0, 1.0)
                result.add(
                    KmMarker(
                        latitude = a.latitude + (b.latitude - a.latitude) * t,
                        longitude = a.longitude + (b.longitude - a.longitude) * t,
                        // Whole-km figure at this boundary (interval is whole km,
                        // so this divides exactly).
                        kilometer = (nextBoundary / METERS_PER_KM).toInt(),
                    ),
                )
                nextBoundary += intervalMeters
                // Backstop: the interval choice already bounds the count, but a
                // floating-point edge must never overrun the drawable cap.
                if (result.size >= MAX_MARKERS) return result
            }
            cumulative = segEnd
        }
        return result
    }

    /**
     * Total driven distance (metres): the sum of the SAME per-segment filtered
     * distance the marker walk uses ([DriveSummary.segmentDistanceMetres]), so
     * the interval chosen for coarsening is based on the real (jump-filtered)
     * length rather than an inflated one.
     */
    private fun totalDistanceMeters(points: List<RoutePoint>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val seg = DriveSummary.segmentDistanceMetres(points[i], points[i + 1])
            if (seg.isFinite() && seg > 0.0) total += seg
        }
        return total
    }

    /**
     * The smallest interval from [INTERVAL_STEPS_KM] that keeps the marker count
     * (≈ totalMeters / interval) at or below [MAX_MARKERS] for a route of
     * [totalMeters]. Normal drives return 1; only extreme lengths step up.
     */
    private fun intervalKmFor(totalMeters: Double): Int {
        for (stepKm in INTERVAL_STEPS_KM) {
            if (totalMeters / (stepKm * METERS_PER_KM) <= MAX_MARKERS) return stepKm
        }
        return INTERVAL_STEPS_KM.last()
    }
}
