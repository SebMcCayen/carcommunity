package com.kungsbackacarcommunity.app.drives

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Saved drives domain (Phase 12 slice 12, read side). Backend computes all
 * stats server-side (the `drives-save` callable); the client only reads
 * owner-scoped `rides/{rideId}` documents and deletes via the `drives-delete`
 * callable. Route GPS data
 * lives in member-gated Cloud Storage and is intentionally NOT read here — the
 * detail view shows a placeholder until the Mapbox route overview lands. Pure
 * Kotlin for testability.
 */
data class SavedDrive(
    val rideId: String,
    val title: String?,
    val distanceMeters: Double?,
    val durationSeconds: Long,
    val averageSpeedMetersPerSecond: Double?,
    val startedAtMillis: Long?,
    val endedAtMillis: Long?,
    val createdAtMillis: Long?,
)

object SavedDrives {
    /** Newest saved first; undated drives sort last. */
    fun sortedForList(drives: List<SavedDrive>): List<SavedDrive> =
        drives.sortedByDescending { it.createdAtMillis ?: Long.MIN_VALUE }
}

/**
 * Pure, locale-stable display formatters for drive stats. Unit labels (km, m,
 * h, min, km/h) are numeric-adjacent and identical in sv/en, so they live here
 * rather than in string resources; the field LABELS come from savedDrives_*.
 */
object DriveFormatters {
    /** Metres → "820 m" under 1 km, otherwise "12.3 km" (one decimal). */
    fun formatDistance(distanceMeters: Double?): String {
        if (distanceMeters == null || distanceMeters < 0) return "—"
        if (distanceMeters < 1000) return "${distanceMeters.roundToInt()} m"
        val km = distanceMeters / 1000.0
        return String.format(Locale.ROOT, "%.1f km", km)
    }

    /** Seconds → "1 h 5 min", "5 min", or "45 s" (drops zero leading units). */
    fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds <= 0) return "0 min"
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 -> "$minutes min"
            else -> "$seconds s"
        }
    }

    /** m/s → "45 km/h" (whole km/h). */
    fun formatSpeed(metersPerSecond: Double?): String {
        if (metersPerSecond == null || !metersPerSecond.isFinite() || metersPerSecond < 0) return "—"
        val kmh = (metersPerSecond * 3.6).roundToInt()
        return "$kmh km/h"
    }

    /**
     * Average speed in m/s: prefer the server-computed value, otherwise derive
     * it from distance / duration so the detail view still shows a figure when
     * the backend didn't persist one. Returns null when neither source is
     * usable (so [formatSpeed] renders the em dash).
     */
    fun effectiveAverageSpeed(
        averageSpeedMetersPerSecond: Double?,
        distanceMeters: Double?,
        durationSeconds: Long,
    ): Double? {
        // A corrupted stored value (Infinity/NaN) must never reach formatSpeed,
        // where it would overflow/wrap the label. Require a finite, non-negative
        // number both for the persisted value and for the distance/duration
        // fallback (a huge distance / tiny duration can also blow up).
        if (averageSpeedMetersPerSecond != null &&
            averageSpeedMetersPerSecond.isFinite() &&
            averageSpeedMetersPerSecond >= 0
        ) {
            return averageSpeedMetersPerSecond
        }
        if (distanceMeters != null &&
            distanceMeters.isFinite() &&
            distanceMeters >= 0 &&
            durationSeconds > 0
        ) {
            val derived = distanceMeters / durationSeconds
            if (derived.isFinite()) return derived
        }
        return null
    }
}
