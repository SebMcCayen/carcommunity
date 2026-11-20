package com.kungsbackacarcommunity.app.map

import kotlin.math.*

/** Presentation only: never feed a rendered coordinate back to recording or sharing. */
object DriverMotionPolicy {
    const val PRESENTATION_DELAY_MS = 250L
    const val CORRECTION_MS = 250L
    const val MAX_PREDICTION_MS = 3_500L
    const val MAX_VELOCITY_SAMPLE_GAP_MS = 5_000L
    const val FRAME_INTERVAL_MS = 16L
    const val MAX_SPEED_MPS = 55.6
}

data class DriverMotionPoint(val latitude: Double, val longitude: Double, val bearing: Double? = null)

/**
 * A local monotonic render timeline, shared by self, nearby and convoy markers.
 * Sample timestamps establish ORDER; [sampleAgeMs] maps a source fix onto this
 * viewer's timeline. A remote publisher's clock is never used as an animation
 * clock. Transport age remains real, rather than being hidden by a long glide.
 */
class DriverMotionTimeline {
    private data class Sample(
        val point: DriverMotionPoint,
        val sourceTime: Long,
        val recordedAt: Long?,
        val speed: Double,
        val course: Double?,
    )
    private var previous: Sample? = null
    private var latest: Sample? = null
    private var correctedAt = 0L
    private var offsetLatitude = 0.0
    private var offsetLongitude = 0.0
    private var offsetBearing = 0.0
    private var lastRenderTime = Long.MIN_VALUE

    fun update(
        point: DriverMotionPoint,
        recordedAt: Long?,
        sampleAgeMs: Long,
        nowMs: Long,
        speedMps: Double? = null,
        courseDegrees: Double? = point.bearing,
    ): Boolean {
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return false
        val before = latest
        if (before?.recordedAt != null && recordedAt != null && recordedAt <= before.recordedAt) return false
        if (recordedAt == null && before?.recordedAt == null && before?.point == point) return false
        val now = maxOf(nowMs, lastRenderTime)
        val oldFrame = frame(now)
        // A future-dated source is not evidence of velocity. Unknown age maps to
        // receipt, while quality/freshness filtering remains at the data seam.
        val sourceTime = now - sampleAgeMs.coerceAtLeast(0L)
        val interval = before?.let { sourceTime - it.sourceTime }
        val estimated = if (before != null && interval != null && interval in 1..DriverMotionPolicy.MAX_VELOCITY_SAMPLE_GAP_MS) {
            distance(before.point, point) * 1_000.0 / interval
        } else null
        val candidateSpeed = speedMps?.takeIf { it.isFinite() && it in 0.0..DriverMotionPolicy.MAX_SPEED_MPS }
            ?: estimated?.takeIf { it.isFinite() && it <= DriverMotionPolicy.MAX_SPEED_MPS } ?: 0.0
        val speed = if (recordedAt != null && sampleAgeMs in 0L..DriverMotionPolicy.MAX_PREDICTION_MS) candidateSpeed else 0.0
        val course = courseDegrees?.takeIf { it.isFinite() }?.let(::normalize)
            ?: if (before != null && estimated != null && estimated > 0.1) course(before.point, point) else null
        previous = before
        latest = Sample(point.copy(bearing = point.bearing?.takeIf { it.isFinite() }?.let(::normalize)), sourceTime, recordedAt, speed, course)
        val target = target(now)
        offsetLatitude = oldFrame?.let { it.latitude - target.latitude } ?: 0.0
        offsetLongitude = oldFrame?.let { delta(target.longitude, it.longitude) } ?: 0.0
        offsetBearing = if (oldFrame?.bearing != null && target.bearing != null) delta(target.bearing, oldFrame.bearing) else 0.0
        correctedAt = now
        return true
    }

    fun frame(nowMs: Long): DriverMotionPoint? {
        if (latest == null) return null
        val now = maxOf(nowMs, lastRenderTime)
        lastRenderTime = now
        val target = target(now)
        val remaining = (1.0 - (now - correctedAt).toDouble() / DriverMotionPolicy.CORRECTION_MS).coerceIn(0.0, 1.0)
        if (remaining == 0.0 || (offsetLatitude == 0.0 && offsetLongitude == 0.0 && offsetBearing == 0.0)) return target
        return DriverMotionPoint(
            latitude = (target.latitude + offsetLatitude * remaining).coerceIn(-90.0, 90.0),
            longitude = wrapLongitude(target.longitude + offsetLongitude * remaining),
            bearing = target.bearing?.let { normalize(it + offsetBearing * remaining) },
        )
    }

    fun isAnimating(nowMs: Long): Boolean {
        val last = latest ?: return false
        val correcting = nowMs - correctedAt < DriverMotionPolicy.CORRECTION_MS &&
            (offsetLatitude != 0.0 || offsetLongitude != 0.0 || offsetBearing != 0.0)
        return correcting || (last.speed > 0.0 && last.course != null &&
            nowMs < last.sourceTime + DriverMotionPolicy.PRESENTATION_DELAY_MS + DriverMotionPolicy.MAX_PREDICTION_MS)
    }

    private fun target(now: Long): DriverMotionPoint {
        val last = requireNotNull(latest)
        val displayTime = now - DriverMotionPolicy.PRESENTATION_DELAY_MS
        val prior = previous
        if (prior != null && last.sourceTime > prior.sourceTime && displayTime < last.sourceTime) {
            val fraction = ((displayTime - prior.sourceTime).toDouble() / (last.sourceTime - prior.sourceTime)).coerceIn(0.0, 1.0)
            return interpolate(prior.point, last.point, fraction)
        }
        val forecastMs = (displayTime - last.sourceTime).coerceIn(0L, DriverMotionPolicy.MAX_PREDICTION_MS)
        val course = last.course ?: return last.point
        if (forecastMs == 0L || last.speed == 0.0) return last.point
        return destination(last.point, course, last.speed * forecastMs / 1_000.0)
    }

    companion object {
        fun interpolate(a: DriverMotionPoint, b: DriverMotionPoint, fraction: Double): DriverMotionPoint {
            if (fraction <= 0.0) return a
            if (fraction >= 1.0) return b
            return DriverMotionPoint(
                a.latitude + (b.latitude - a.latitude) * fraction,
                if (a.longitude == b.longitude) a.longitude else wrapLongitude(a.longitude + delta(a.longitude, b.longitude) * fraction),
                if (a.bearing != null && b.bearing != null) normalize(a.bearing + delta(a.bearing, b.bearing) * fraction) else b.bearing ?: a.bearing,
            )
        }
        private fun normalize(value: Double) = (value % 360.0 + 360.0) % 360.0
        private fun wrapLongitude(value: Double) = ((value + 540.0) % 360.0 + 360.0) % 360.0 - 180.0
        private fun delta(from: Double, to: Double) = normalize(to - from + 180.0) - 180.0
        private fun distance(a: DriverMotionPoint, b: DriverMotionPoint): Double {
            val lat = Math.toRadians(b.latitude - a.latitude)
            val lon = Math.toRadians(delta(a.longitude, b.longitude))
            val hav = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
            return 6_371_000.0 * 2 * asin(sqrt(hav.coerceIn(0.0, 1.0)))
        }
        private fun course(a: DriverMotionPoint, b: DriverMotionPoint): Double {
            val lon = Math.toRadians(delta(a.longitude, b.longitude))
            val aLat = Math.toRadians(a.latitude)
            val bLat = Math.toRadians(b.latitude)
            return normalize(Math.toDegrees(atan2(sin(lon) * cos(bLat), cos(aLat) * sin(bLat) - sin(aLat) * cos(bLat) * cos(lon))))
        }
        private fun destination(point: DriverMotionPoint, bearing: Double, meters: Double): DriverMotionPoint {
            val radius = meters / 6_371_000.0
            val lat = Math.toRadians(point.latitude)
            val angle = Math.toRadians(bearing)
            val nextLat = asin((sin(lat) * cos(radius) + cos(lat) * sin(radius) * cos(angle)).coerceIn(-1.0, 1.0))
            val nextLon = Math.toRadians(point.longitude) + atan2(sin(angle) * sin(radius) * cos(lat), cos(radius) - sin(lat) * sin(nextLat))
            return DriverMotionPoint(Math.toDegrees(nextLat), wrapLongitude(Math.toDegrees(nextLon)), point.bearing ?: bearing)
        }
    }
}
