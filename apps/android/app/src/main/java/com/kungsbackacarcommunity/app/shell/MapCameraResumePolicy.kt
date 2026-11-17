package com.kungsbackacarcommunity.app.shell

import kotlin.math.cos
import kotlin.math.hypot

/** Raw provider receipt, before the SDK interpolates any display positions. */
internal data class MapCameraSourceFix(
    val point: MapPoint,
    val sequence: Long,
    val receivedAtMs: Long,
    /** Physical fix timestamp when the recorder owns location; SDK Point[] has none. */
    val capturedAtMs: Long? = null,
    val speedMps: Double? = null,
)

/** Camera-only lifecycle state; never delays or changes a recorded location. */
internal class MapCameraResumePolicy {
    private var visible = false
    private var anchor: MapPoint? = null
    private var anchorMs = 0L
    private var movingUntilMs = Long.MIN_VALUE
    private var hiddenSourceSequence: Long? = null
    private var lastPositionSequence: Long? = null
    var pending: Boolean = false
        private set

    fun visibilityChanged(visible: Boolean, following: Boolean, sourceSequence: Long?) {
        if (this.visible == visible) return
        this.visible = visible
        pending = visible && following
        if (!visible) hiddenSourceSequence = sourceSequence
        lastPositionSequence = null
        anchor = null
        movingUntilMs = Long.MIN_VALUE
    }

    fun cancel() { pending = false }

    /** Cache is identified by its source receipt, never by coordinate equality. */
    fun takeReturn(sourceSequence: Long?, fresh: Boolean): Boolean {
        if (!visible || !pending || !fresh) return false
        if (sourceSequence != null && hiddenSourceSequence?.let { sourceSequence <= it } == true) return false
        pending = false
        return true
    }

    fun position(point: MapPoint, nowMs: Long, speedMps: Double? = null, sourceSequence: Long? = null) {
        if (!visible || (sourceSequence != null && lastPositionSequence?.let { sourceSequence <= it } == true)) return
        lastPositionSequence = sourceSequence
        val previous = anchor
        val elapsed = nowMs - anchorMs
        if (speedMps != null && speedMps.isFinite()) {
            movingUntilMs = if (speedMps >= MIN_COURSE_SPEED_MPS) nowMs + MOVEMENT_HOLD_MS else Long.MIN_VALUE
        } else if (previous != null && elapsed in 1L..15_000L) {
            val latitude = (point.latitude + previous.latitude) / 2 * Math.PI / 180
            val meters = hypot((point.latitude - previous.latitude) * 111_195,
                (point.longitude - previous.longitude) * 111_195 * cos(latitude))
            if (meters >= 5 && meters / (elapsed / 1_000.0) >= MIN_COURSE_SPEED_MPS) {
                movingUntilMs = nowMs + MOVEMENT_HOLD_MS
                anchor = point
                anchorMs = nowMs
            }
        }
        if (previous == null || elapsed > 15_000 || speedMps != null) {
            anchor = point
            anchorMs = nowMs
        }
    }

    fun acceptsCourse(nowMs: Long): Boolean = visible && nowMs <= movingUntilMs

    companion object {
        const val RETURN_DURATION_MS = 650L
        const val MAX_FIX_AGE_MS = 5_000L
        const val MIN_COURSE_SPEED_MPS = 1.4
        private const val MOVEMENT_HOLD_MS = 2_000L

        fun bearingBetween(from: Double, to: Double, fraction: Double): Double {
            val delta = (to - from + 540) % 360 - 180
            return ((from + delta * fraction.coerceIn(0.0, 1.0)) % 360 + 360) % 360
        }
    }
}
