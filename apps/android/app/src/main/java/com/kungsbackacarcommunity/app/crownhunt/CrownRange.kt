package com.kungsbackacarcommunity.app.crownhunt

/**
 * The single, pure "is the member close enough to collect this crown?" decision,
 * shared by the map layer (which greys an out-of-range crown — [CrownMarkerStyle])
 * and the tap popups (which grey the Collect button until the member is in range).
 *
 * ## Why its own object rather than reusing [CrownCollectGate]
 *
 * [CrownCollectGate] answers a RICHER question — distance AND stillness AND the
 * feature flag — because a Collect *press* must be honestly gated on all three.
 * A map *marker* only needs the distance half: a crown you are parked 5 m from is
 * "reachable" and lights up, whether or not you happen to be rolling at that
 * instant, and greying every crown the moment the car moves would make the whole
 * layer flicker grey at every red light. So the marker colour and the "is the
 * button offered" hint use THIS (distance only), and the button's final
 * enablement still goes through [CrownCollectGate] (which adds the stop rule).
 * The radius resolution is identical, so the two never disagree about where the
 * ring is.
 *
 * Pure Kotlin — no Android, no Firebase — so the in/out decision (and therefore
 * "coloured vs grey", "enabled vs disabled") is pinned by JVM unit tests rather
 * than tried out in a moving car.
 */
object CrownRange {
    /**
     * Whether [distanceMeters] is within the crown's collect radius.
     *
     * A null or non-finite distance (no fix, or a broken reading) is NOT in range
     * — the crown greys and the button stays disabled — because "we cannot tell"
     * must fail closed exactly as [CrownCollectGate] treats it. The radius is put
     * through [CrownSpawnLimits.resolveCollectRadiusMeters] so a crown whose
     * document carries a missing or absurd radius narrows to the mirrored 75 m
     * default rather than widening the ring.
     */
    fun isInRange(
        distanceMeters: Double?,
        collectRadiusMeters: Double = CrownSpawnLimits.COLLECT_RADIUS_METERS,
    ): Boolean {
        if (distanceMeters == null || !distanceMeters.isFinite()) return false
        val radius = CrownSpawnLimits.resolveCollectRadiusMeters(collectRadiusMeters)
        return distanceMeters <= radius
    }

    /**
     * Whether the member at ([userLat], [userLon]) is within a crown's collect
     * radius of ([crownLat], [crownLon]). Uses the same great-circle distance the
     * rest of the app does ([CrownSpawnQuery.distanceMeters]), so a crown that
     * greys here is exactly a crown the popup calls "too far".
     */
    fun isInRange(
        userLat: Double,
        userLon: Double,
        crownLat: Double,
        crownLon: Double,
        collectRadiusMeters: Double,
    ): Boolean =
        isInRange(
            CrownSpawnQuery.distanceMeters(userLat, userLon, crownLat, crownLon),
            collectRadiusMeters,
        )
}
