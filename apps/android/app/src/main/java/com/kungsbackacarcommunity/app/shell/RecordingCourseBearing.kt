package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry
import com.kungsbackacarcommunity.app.navigation.turnbyturn.NavFollowBearing

/** Travel direction from current-boot accepted fixes; never from the phone's orientation. */
internal class RecordingCourseBearing {
    private var anchor: MapRecordingPosition? = null
    private var lastElapsedMs: Long? = null
    private var bearing: Double? = null

    fun update(fix: MapRecordingPosition): Double? {
        if (lastElapsedMs?.let { fix.elapsedRealtimeMs <= it } == true) return bearing
        lastElapsedMs = fix.elapsedRealtimeMs
        val previous = anchor
        val elapsedMs = previous?.let { fix.elapsedRealtimeMs - it.elapsedRealtimeMs } ?: 0L
        if (previous == null || elapsedMs !in 1L..MAX_SEGMENT_AGE_MS) anchor = fix

        // A stationary GPS course can be present but meaningless. Hold the last
        // moving direction, and start a new displacement anchor when pulling away.
        val speed = fix.speedMps?.takeIf { it.isFinite() && it >= 0.0 }
        if (speed != null && speed < NavFollowBearing.MIN_COURSE_SPEED_MPS) {
            anchor = fix
            return bearing
        }
        val course = fix.bearingDegrees?.takeIf { it.isFinite() && it in 0.0..360.0 }
        val courseAccuracy = fix.bearingAccuracyDegrees
        val accurateCourse = courseAccuracy == null ||
            (courseAccuracy.isFinite() && courseAccuracy in 0.0..MAX_COURSE_ACCURACY_DEGREES)
        if (NavFollowBearing.isCourseTrustworthy(course, speed) && accurateCourse) {
            anchor = fix
            bearing = NavFollowBearing.normalizeDeg(requireNotNull(course))
            return bearing
        }

        // Some providers omit course even at driving speed. Accumulate movement
        // beyond both fixes' accuracy radii instead of freezing the old heading
        // indefinitely or turning GPS jitter into a direction while parked.
        if (previous == null || elapsedMs !in 1L..MAX_SEGMENT_AGE_MS) return bearing
        val distance = ConvoyEdgeGeometry.distanceMeters(
            previous.point.latitude, previous.point.longitude, fix.point.latitude, fix.point.longitude,
        )
        val uncertainty = previous.accuracyMeters + fix.accuracyMeters
        if (!uncertainty.isFinite() || previous.accuracyMeters < 0.0 || fix.accuracyMeters < 0.0 ||
            distance <= maxOf(MIN_DISPLACEMENT_METERS, uncertainty) ||
            distance / (elapsedMs / 1000.0) < NavFollowBearing.MIN_COURSE_SPEED_MPS
        ) return bearing
        anchor = fix
        bearing = ConvoyEdgeGeometry.initialBearingDegrees(
            previous.point.latitude, previous.point.longitude, fix.point.latitude, fix.point.longitude,
        )
        return bearing
    }

    companion object {
        private const val MIN_DISPLACEMENT_METERS = 5.0
        private const val MAX_COURSE_ACCURACY_DEGREES = 45.0
        private const val MAX_SEGMENT_AGE_MS = 15_000L
    }
}
