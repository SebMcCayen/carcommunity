package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry

/**
 * The wave-eligibility radius, in metres — the client's VISIBILITY bound on who the
 * wave control may target, kept in lock-step with the SERVER's WAVE_RADIUS_METERS
 * (functions/src/live/wave-core.ts). The server is the source of truth for wave
 * DELIVERY: `live-sendWave` fans a wave out only to live sharers within this radius
 * of the SENDER'S OWN authoritative position (from `liveSessions`). This client
 * constant mirrors that delivery bound so the wave affordance is offered only for
 * members the wave could actually reach.
 *
 * Deliberately SEPARATE from [DEFAULT_NEARBY_RADIUS_METERS]
 * (live/LiveLocationRepository.kt), which is the radius of the camera-centred nearby
 * DISCOVERY poll — a different concept (how far around the MAP CAMERA to look for
 * sharers to draw). Both are 15 km today but answer different questions; do not fold
 * them together (see #1039 — zooming the camera to a distant sharer must NOT make
 * them wave-eligible).
 */
const val WAVE_RADIUS_METERS: Double = 15_000.0

/**
 * PURE decisions for the wave-to-nearby-live-users control — no Compose, no
 * Firebase — so the visibility gate and the client cooldown mirror are
 * unit-testable off the UI ([WavePresenceTest]).
 */
object WavePresence {
    /**
     * The subset of [nearby] live markers within [radiusMeters] of the user's OWN
     * position ([ownLat]/[ownLng]) — those the wave could actually reach, matching the
     * server's delivery bound. Returns EMPTY when the own position is unknown
     * ([ownLat] or [ownLng] null): without an authoritative origin we cannot tell who
     * is in range, and the server would have nothing to broadcast from either.
     *
     * This is what bounds the wave AFFORDANCE to your own range. The map still draws
     * every nearby sharer wherever you pan (the camera-centred roster); only the wave
     * control's visibility is filtered through here — fixing the "wave from Norway to
     * Sweden" bug (#1039) where zooming to a distant member wrongly lit the control.
     */
    fun waveEligibleInRange(
        ownLat: Double?,
        ownLng: Double?,
        nearby: List<LiveMarker>,
        radiusMeters: Double = WAVE_RADIUS_METERS,
    ): List<LiveMarker> =
        filterWithinRange(ownLat, ownLng, nearby, radiusMeters, { it.latitude }, { it.longitude })

    /**
     * As [waveEligibleInRange], but over the fuller nearby-DISCOVERY roster
     * ([NearbyLiveSession], up to listNearby's 200) that drives the wave range gate —
     * so the gate's "already waved this visit" bookkeeping and the visible control
     * agree on the SAME own-position eligibility bound.
     */
    fun waveEligibleSessionsInRange(
        ownLat: Double?,
        ownLng: Double?,
        nearby: List<NearbyLiveSession>,
        radiusMeters: Double = WAVE_RADIUS_METERS,
    ): List<NearbyLiveSession> =
        filterWithinRange(ownLat, ownLng, nearby, radiusMeters, { it.latitude }, { it.longitude })

    /**
     * Shared core: keeps items whose extracted coordinate is within [radiusMeters] of
     * the own position, or none at all when the own position is unknown. Distance is
     * the NaN-clamped great-circle helper [ConvoyEdgeGeometry.distanceMeters] rather
     * than a second haversine, so wave range and the map's convoy geometry can never
     * drift apart.
     */
    private fun <T> filterWithinRange(
        ownLat: Double?,
        ownLng: Double?,
        items: List<T>,
        radiusMeters: Double,
        lat: (T) -> Double,
        lng: (T) -> Double,
    ): List<T> {
        if (ownLat == null || ownLng == null) return emptyList()
        return items.filter {
            ConvoyEdgeGeometry.distanceMeters(ownLat, ownLng, lat(it), lng(it)) <= radiusMeters
        }
    }

    /**
     * Whether the wave control should be shown. It appears ONLY when you are
     * yourself sharing a live session AND at least one in-range live user is still
     * WAVEABLE this visit — [waveableInRangeCount], the count of nearby drivers not
     * already waved during their current in-range visit (see [WaveRangeGate]). This
     * mirrors the two conditions the server requires (you must be sharing to have an
     * authoritative origin, and a wave with nobody eligible reaches no one) AND adds
     * the per-target range gate: once everyone in range has been waved the control
     * hides until a fresh driver appears or a waved one leaves and re-enters range.
     * Showing it otherwise would offer a button that only re-spams the same people.
     */
    fun isWaveControlVisible(isSharingLive: Boolean, waveableInRangeCount: Int): Boolean =
        isSharingLive && waveableInRangeCount > 0

    /**
     * Whether a tap may send right now: only once the client cooldown mirror at
     * [cooldownUntilMs] has elapsed. The SERVER is the real gate (it refuses an
     * early send); this just greys the icon so it dims the instant you tap.
     */
    fun isSendEnabled(nowMs: Long, cooldownUntilMs: Long): Boolean = nowMs >= cooldownUntilMs

    /**
     * The cooldown deadline to grey the icon until, after an optimistic send —
     * [nowMs] + [windowMs] (the client mirror of the server window by default).
     */
    fun cooldownUntil(nowMs: Long, windowMs: Long = WAVE_COOLDOWN_MS): Long = nowMs + windowMs
}

/**
 * PER-TARGET, range-based anti-spam gate for the wave control — the UX rule that
 * you may wave a given nearby driver only ONCE per in-range visit.
 *
 * The 45 s server cooldown ([WAVE_COOLDOWN_MS]) is a time gate on the single
 * broadcast button: when it lapses you could wave the SAME driver again while they
 * are still right next to you, which spams them. This gate layers on top of (does
 * NOT replace) that server backstop: once you wave, every driver currently in
 * range is remembered as "already waved THIS visit" and the wave is no longer
 * offered for them. Only when a driver LEAVES wave range (drops out of the
 * in-range set) is their mark cleared, so coming back INTO range later re-enables
 * waving them.
 *
 * A pure, Compose-free, Firebase-free holder so the rule is unit-testable off the
 * UI ([WaveRangeGateTest]). The caller drives it from the SAME nearby/in-range
 * roster that decides whether the wave affordance appears at all, so range
 * exit/re-entry is detected against the true wave-eligibility set.
 */
class WaveRangeGate {
    // Drivers we have already waved during their CURRENT in-range visit. A uid is
    // added on a wave and removed the instant it leaves the in-range set.
    private val wavedThisVisit = mutableSetOf<String>()

    /**
     * Records that [uid] has been waved during their current visit, so the wave is
     * no longer offered for them until they leave and re-enter range.
     */
    fun onWaved(uid: String) {
        wavedThisVisit.add(uid)
    }

    /**
     * Records that EVERY uid in [uids] has been waved this visit — the broadcast
     * case, where one tap waves every driver currently in range at once.
     */
    fun onWaved(uids: Collection<String>) {
        wavedThisVisit.addAll(uids)
    }

    /**
     * Reconciles with the CURRENT in-range roster [currentInRangeUids]: any driver
     * we had marked waved who is no longer in range is forgotten, so if they come
     * back INTO range later the wave is offered again. Drivers still in range keep
     * their mark. Call this whenever the in-range set changes.
     */
    fun onRangeSet(currentInRangeUids: Collection<String>) {
        wavedThisVisit.retainAll(currentInRangeUids.toSet())
    }

    /**
     * Whether the wave may be offered for [uid] right now: true unless they have
     * already been waved during their current in-range visit.
     */
    fun canWave(uid: String): Boolean = uid !in wavedThisVisit

    /**
     * How many of [inRangeUids] are still waveable this visit — the count the
     * visibility gate uses in place of the raw nearby count, so the control hides
     * once every in-range driver has already been waved and reappears only when a
     * not-yet-waved driver is around.
     */
    fun waveableCount(inRangeUids: Collection<String>): Int = inRangeUids.count { canWave(it) }
}
