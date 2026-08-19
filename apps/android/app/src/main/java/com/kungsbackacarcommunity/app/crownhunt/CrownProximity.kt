package com.kungsbackacarcommunity.app.crownhunt

/**
 * How "close to collecting" a crown is, as a 0..1 fill — the pure maths behind the
 * proximity LOADING BAR the popup shows in place of a flat "you're too far" line.
 *
 * The bar is presentation only. It never decides whether a crown can be collected
 * — that stays with [CrownCollectGate] (distance AND stillness AND the flag) and
 * [CrownRange] (the marker's in/out colour). This just turns the ONE number the
 * feature already shows a member — the distance to the crown — into a fraction a
 * bar can fill, so "get closer" is something you watch fill rather than a sentence
 * you re-read.
 *
 * ## The mapping
 *
 * Empty when far, full at (or inside) the crown's own collect ring, and a smooth
 * linear climb over the last [APPROACH_WINDOW_METERS] of the approach:
 *
 * - `distance <= radius`         → **1.0** (you are in range; the bar is full and
 *   the Collect button is about to pop — see [CrownSpawnPopup]).
 * - `distance >= radius + window` → **0.0** (still far; nothing to watch yet).
 * - between the two              → linear, `(radius + window - distance) / window`.
 *
 * The radius is put through [CrownSpawnLimits.resolveCollectRadiusMeters], the SAME
 * resolver the gate and the marker use, so the point at which the bar hits full is
 * exactly the point at which the crown lights up and the button can go live — the
 * bar can never say "full" while the button is still greyed for range.
 *
 * A null-shaped distance (non-finite, or a broken negative reading) reads as
 * **empty**, failing closed exactly as [CrownRange] and [CrownCollectGate] do: an
 * unknown position is never drawn as progress.
 *
 * Pure Kotlin — no Android, no Compose — so the whole curve (endpoints, clamping,
 * the fail-closed cases) is pinned by JVM unit tests rather than eyeballed on a bar.
 */
object CrownProximity {
    /**
     * How many metres of approach the bar fills over. Beyond `radius + this` the
     * bar is empty; from here in it climbs to full at the ring. Chosen so the fill
     * covers the last short stretch of getting there (a minute-ish of driving, or
     * a walk across a car park) rather than crawling for kilometres — a member far
     * away sees an empty bar and the distance text, and the bar only starts to move
     * once arriving is actually in reach.
     */
    const val APPROACH_WINDOW_METERS: Double = 500.0

    /**
     * The 0..1 fill for [distanceMeters] against the crown's collect [radiusMeters]
     * (resolved through [CrownSpawnLimits.resolveCollectRadiusMeters]) over an
     * [approachMeters] window. Clamped to `0f..1f`; see the class KDoc for the map.
     */
    fun proximityFraction(
        distanceMeters: Double,
        radiusMeters: Double = CrownSpawnLimits.COLLECT_RADIUS_METERS,
        approachMeters: Double = APPROACH_WINDOW_METERS,
    ): Float {
        // Unknown / broken position → empty, fail closed like CrownRange.
        if (!distanceMeters.isFinite() || distanceMeters < 0.0) return 0f
        val radius = CrownSpawnLimits.resolveCollectRadiusMeters(radiusMeters)
        // A broken window can only ever collapse the bar to its endpoints (full in
        // range, empty out of it), never invert or widen it.
        val window =
            if (approachMeters.isFinite() && approachMeters > 0.0) approachMeters else APPROACH_WINDOW_METERS
        if (distanceMeters <= radius) return 1f
        val outer = radius + window
        if (distanceMeters >= outer) return 0f
        return ((outer - distanceMeters) / window).toFloat().coerceIn(0f, 1f)
    }

    /**
     * How far is left to REACH THE RING — `max(distance - radius, 0)` — for the
     * "x m to go" label beside the bar.
     *
     * The raw distance is to the crown's CENTRE, but the member only has to reach
     * the collect ring, so the honest "to go" is the gap to the ring's edge: at
     * 76 m against a 75 m radius there is ~1 m left, not 76. It reaches **0** at (or
     * inside) the ring, so the label lands on "0 m" exactly as the bar fills — the
     * two never disagree, because both resolve the radius the same way
     * ([CrownSpawnLimits.resolveCollectRadiusMeters]) and read the same distance.
     *
     * A null-shaped distance collapses to 0, matching how the bar reads an unknown
     * position (nothing to travel that can be drawn).
     */
    fun remainingToRingMeters(
        distanceMeters: Double,
        radiusMeters: Double = CrownSpawnLimits.COLLECT_RADIUS_METERS,
    ): Double {
        if (!distanceMeters.isFinite() || distanceMeters < 0.0) return 0.0
        val radius = CrownSpawnLimits.resolveCollectRadiusMeters(radiusMeters)
        return (distanceMeters - radius).coerceAtLeast(0.0)
    }
}
