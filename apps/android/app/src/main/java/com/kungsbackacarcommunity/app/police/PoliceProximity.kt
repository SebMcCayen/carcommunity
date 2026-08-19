package com.kungsbackacarcommunity.app.police

import com.kungsbackacarcommunity.app.incidents.ViewportRadius
import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * PURE proximity-alert decision for the police-proximity feature — no Compose, no
 * Android, no Firebase — so the "within threshold + not-already-alerted → alert,
 * ONCE per pin" rule is unit-testable off-device ([PoliceProximityTest]).
 *
 * The alert fires the mid-screen police [com.kungsbackacarcommunity.app.design.ReactionOverlay]
 * when the driver comes CLOSE to a live police pin. Two rules keep it useful
 * rather than noisy:
 *  - DISTANCE: the pin must be within [ALERT_RADIUS_METERS] of the driver, using
 *    the shared [ViewportRadius.haversineMeters] great-circle distance (the same
 *    maths the rest of the map trusts — never a re-implemented distance).
 *  - ONCE PER PIN: a pin that has already alerted this driver never fires again.
 *    The caller keeps an accumulating set of alerted pin ids and passes it back on
 *    every location update, so a driver sitting next to a patrol is warned once,
 *    not on every GPS tick.
 *  - NOT YOUR OWN: a pin the driver reported themselves ([PoliceReport.mine]) never
 *    fires — you don't need a mid-screen warning about the patrol you just tapped.
 *    The pin is still DRAWN on the map (that suppression is only for the alert).
 *
 * Liveness is the caller's responsibility (the pins come from a listNearby that
 * already excludes expired ones); [PoliceReport.isLiveAt] is available for a
 * belt-and-braces client-side filter.
 */
object PoliceProximity {
    /**
     * How close (metres) a driver must come to a live police pin before the alert
     * fires. 500 m ≈ 20–25 s of warning at 70–90 km/h: enough to register and
     * react, not so early the patrol is out of sight and the alert reads as noise.
     * Kept in sync with the backend's POLICE_PROXIMITY_ALERT_RADIUS_METERS.
     */
    const val ALERT_RADIUS_METERS = 500.0

    /**
     * The pins that should fire an alert NOW: every live pin within
     * [radiusMeters] of [driver] whose id is NOT already in [alreadyAlerted],
     * de-duplicated by id. Returns them in input order so the caller can fire the
     * nearest-first if it wishes; in practice it fires one and records the rest.
     *
     * A null driver location (no fix yet) yields nothing. A pin with a corrupt
     * coordinate is skipped (never alerts) rather than throwing. A pin the driver
     * reported themselves ([PoliceReport.mine]) is skipped too — no self-alert.
     */
    fun newAlerts(
        driver: LatLng?,
        pins: List<PoliceReport>,
        alreadyAlerted: Set<String>,
        radiusMeters: Double = ALERT_RADIUS_METERS,
    ): List<PoliceReport> {
        if (driver == null) return emptyList()
        if (!driver.latitude.isFinite() || !driver.longitude.isFinite()) return emptyList()
        val seen = HashSet<String>(alreadyAlerted)
        val out = ArrayList<PoliceReport>()
        for (pin in pins) {
            if (pin.id in seen) continue
            // Never alert a driver about their OWN reported pin (still drawn, just
            // not warned about). This is the cross-session/device-correct suppression
            // — the server resolves ownership per caller, so it holds even after an
            // app restart when the client-only "ids I reported" memory is gone.
            if (pin.mine) continue
            if (!pin.latitude.isFinite() || !pin.longitude.isFinite()) continue
            val distance =
                ViewportRadius.haversineMeters(
                    driver.latitude,
                    driver.longitude,
                    pin.latitude,
                    pin.longitude,
                )
            if (distance <= radiusMeters) {
                out.add(pin)
                // Guard against the same id appearing twice in one batch.
                seen.add(pin.id)
            }
        }
        return out
    }

    /**
     * True when [driver] is within [radiusMeters] of the single [pin]. The
     * one-pin core [newAlerts] is built on, exposed for direct testing.
     */
    fun isWithinRange(
        driver: LatLng,
        pin: PoliceReport,
        radiusMeters: Double = ALERT_RADIUS_METERS,
    ): Boolean {
        if (!driver.latitude.isFinite() || !driver.longitude.isFinite()) return false
        if (!pin.latitude.isFinite() || !pin.longitude.isFinite()) return false
        return ViewportRadius.haversineMeters(
            driver.latitude,
            driver.longitude,
            pin.latitude,
            pin.longitude,
        ) <= radiusMeters
    }
}
