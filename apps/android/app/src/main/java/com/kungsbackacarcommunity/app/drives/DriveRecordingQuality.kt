package com.kungsbackacarcommunity.app.drives

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Raw recording input. Monotonic clocks are local to one device boot and never uploaded. */
data class DriveLocationSample(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val receivedElapsedRealtimeMs: Long,
    val receivedAtMillis: Long,
    val accuracyMeters: Double?,
    val speedMps: Double? = null,
)

/** Coordinate-free, bounded counters. Same wire shape as iOS and the save callable. */
data class DriveRecordingDiagnostics(
    val receivedPoints: Int = 0,
    val acceptedPoints: Int = 0,
    val rejectedInvalid: Int = 0,
    val rejectedAccuracy: Int = 0,
    val rejectedStale: Int = 0,
    val rejectedTime: Int = 0,
    val rejectedSpeed: Int = 0,
    val droppedCapacity: Int = 0,
    val droppedStationary: Int = 0,
    val droppedThrottled: Int = 0,
    val maxGapMs: Long = 0,
    val gapCount: Int = 0,
    val resumedCount: Int = 0,
    /** Recording origin, captured at Start; unrelated to the device reporting a bug. */
    val platform: String? = null,
    val appVersion: String? = null,
) {
    fun toWire(): Map<String, Any> =
        linkedMapOf(
            "version" to 1,
            "algorithmVersion" to ALGORITHM_VERSION,
            "receivedPoints" to receivedPoints,
            "acceptedPoints" to acceptedPoints,
            "rejectedInvalid" to rejectedInvalid,
            "rejectedAccuracy" to rejectedAccuracy,
            "rejectedStale" to rejectedStale,
            "rejectedTime" to rejectedTime,
            "rejectedSpeed" to rejectedSpeed,
            "droppedCapacity" to droppedCapacity,
            "droppedStationary" to droppedStationary,
            "droppedThrottled" to droppedThrottled,
            "maxGapMs" to maxGapMs,
            "gapCount" to gapCount,
            "resumedCount" to resumedCount,
        ).apply {
            validPlatform(platform)?.let { put("platform", it) }
            validAppVersion(appVersion)?.let { put("appVersion", it) }
        }

    /** D1 counters remain readable without optional recording-origin metadata. */
    fun journalLine(): String {
        val counters = "D,1," + listOf(
            receivedPoints, acceptedPoints, rejectedInvalid, rejectedAccuracy,
            rejectedStale, rejectedTime, rejectedSpeed, droppedCapacity,
            droppedStationary, droppedThrottled, maxGapMs, gapCount, resumedCount,
        ).joinToString(",")
        val recordingPlatform = validPlatform(platform)
        val recordingVersion = validAppVersion(appVersion)
        if (recordingPlatform == null && recordingVersion == null) return counters
        val encodedVersion = recordingVersion?.let {
            Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
        } ?: "-"
        return "$counters,M1,${recordingPlatform ?: "-"},$encodedVersion"
    }

    companion object {
        const val ALGORITHM_VERSION = "recording-quality-v1"
        const val MAX_COUNT = 10_000_000
        const val MAX_GAP_MS = 604_800_000L
        const val MAX_CHECKPOINT_CHARACTERS = 1_024

        fun validAppVersion(value: String?): String? =
            value?.takeIf { it.length in 1..50 && it.isNotBlank() && it.none(Char::isISOControl) }

        private fun validPlatform(value: String?): String? = value?.takeIf { it == "android" || it == "ios" }

        private fun decodeVersion(value: String): String? = runCatching {
            val bytes = Base64.getDecoder().decode(value)
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            validAppVersion(decoded)
        }.getOrNull()

        fun fromJournalLine(line: String): DriveRecordingDiagnostics? {
            val values = line.take(MAX_CHECKPOINT_CHARACTERS).split(',')
            if (values.size < 15 || values[0] != "D" || values[1] != "1") return null
            val numbers = values.subList(2, 15).map { it.toLongOrNull() ?: return null }
            // Optional metadata corruption cannot discard valid route/counter facts.
            val hasMetadata = values.size == 18 && values[15] == "M1"
            val recordingPlatform = if (hasMetadata) validPlatform(values[16]) else null
            val recordingVersion = if (hasMetadata) decodeVersion(values[17]) else null
            if (numbers.withIndex().any { (index, value) ->
                    value !in 0..(if (index == 10) MAX_GAP_MS else MAX_COUNT.toLong())
                }
            ) return null
            return DriveRecordingDiagnostics(
                numbers[0].toInt(), numbers[1].toInt(), numbers[2].toInt(),
                numbers[3].toInt(), numbers[4].toInt(), numbers[5].toInt(),
                numbers[6].toInt(), numbers[7].toInt(), numbers[8].toInt(),
                numbers[9].toInt(), numbers[10], numbers[11].toInt(), numbers[12].toInt(),
                platform = recordingPlatform,
                appVersion = recordingVersion,
            )
        }
    }
}

/**
 * Recording-only quality gate. A rejected point never advances the trusted anchor,
 * so one GPS excursion cannot poison both adjacent distance segments (#1348).
 * No stationary deadband: genuine slow motion remains recordable. Long missing
 * paths are not reconstructed; the existing server distance model is unchanged.
 */
class DriveRecordingQuality(
    initial: DriveRecordingDiagnostics = DriveRecordingDiagnostics(),
    private val restoredLastPoint: RecordedPoint? = null,
) {
    var diagnostics: DriveRecordingDiagnostics = initial
        private set

    private var lastReceiptMs: Long? = null
    private var epochAnchorMs: Long? = null
    private var sampleAnchorMs: Long? = null
    private var trusted: DriveLocationSample? = null

    fun process(sample: DriveLocationSample, capacityAvailable: Boolean): RecordedPoint? {
        val receipt = lastReceiptMs
        val receiptGap = receipt?.let { sample.receivedElapsedRealtimeMs - it }
        diagnostics = diagnostics.copy(
            receivedPoints = increment(diagnostics.receivedPoints),
            maxGapMs = maxOf(diagnostics.maxGapMs, (receiptGap ?: 0).coerceIn(0, DriveRecordingDiagnostics.MAX_GAP_MS)),
            gapCount = if (receiptGap != null && receiptGap > GAP_THRESHOLD_MS) increment(diagnostics.gapCount) else diagnostics.gapCount,
        )
        if (receipt == null || sample.receivedElapsedRealtimeMs > receipt) lastReceiptMs = sample.receivedElapsedRealtimeMs
        if (!capacityAvailable) {
            diagnostics = diagnostics.copy(droppedCapacity = increment(diagnostics.droppedCapacity))
            return null
        }
        if (!sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0 ||
            sample.timestampMs <= 0 || sample.elapsedRealtimeMs < 0 || sample.receivedElapsedRealtimeMs < 0
        ) {
            diagnostics = diagnostics.copy(rejectedInvalid = increment(diagnostics.rejectedInvalid))
            return null
        }
        val ageMs = sample.receivedElapsedRealtimeMs - sample.elapsedRealtimeMs
        if (ageMs !in -5_000L..15_000L) {
            diagnostics = diagnostics.copy(rejectedStale = increment(diagnostics.rejectedStale))
            return null
        }
        val accuracy = sample.accuracyMeters
        if (accuracy == null || !accuracy.isFinite() || accuracy !in 0.0..MAX_ACCURACY_METERS) {
            diagnostics = diagnostics.copy(rejectedAccuracy = increment(diagnostics.rejectedAccuracy))
            return null
        }
        val previous = trusted
        if (previous != null) {
            val deltaMs = sample.elapsedRealtimeMs - previous.elapsedRealtimeMs
            if (deltaMs <= 0) {
                diagnostics = diagnostics.copy(rejectedTime = increment(diagnostics.rejectedTime))
                return null
            }
            val distance = DriveSummary.haversineMetres(previous.latitude, previous.longitude, sample.latitude, sample.longitude)
            if (distance / (deltaMs / 1000.0) > DriveSummary.MAX_PLAUSIBLE_SPEED_MPS) {
                diagnostics = diagnostics.copy(rejectedSpeed = increment(diagnostics.rejectedSpeed))
                return null
            }
        }
        // Provider Unix clocks may differ or jump. Anchor once to initial receipt
        // wall time minus MONOTONIC age; all following intervals use sample elapsed
        // time. Restore starts a new clock domain, never reuses elapsed across boot.
        val baseEpoch = epochAnchorMs ?: (sample.receivedAtMillis - ageMs)
        val baseSample = sampleAnchorMs ?: sample.elapsedRealtimeMs
        val normalized = baseEpoch + (sample.elapsedRealtimeMs - baseSample)
        if (normalized <= 0 || (restoredLastPoint != null && normalized <= restoredLastPoint.timestampMs)) {
            diagnostics = diagnostics.copy(rejectedTime = increment(diagnostics.rejectedTime))
            return null
        }
        if (previous == null && restoredLastPoint != null) {
            val deltaMs = normalized - restoredLastPoint.timestampMs
            // Across a restart we cannot compare boot clocks. A short positive
            // epoch interval still permits a conservative teleport sanity check;
            // a long missing path is left unknown rather than reconstructed.
            if (deltaMs <= GAP_THRESHOLD_MS &&
                DriveSummary.haversineMetres(restoredLastPoint.latitude, restoredLastPoint.longitude, sample.latitude, sample.longitude) /
                (deltaMs / 1000.0) > DriveSummary.MAX_PLAUSIBLE_SPEED_MPS
            ) {
                diagnostics = diagnostics.copy(rejectedSpeed = increment(diagnostics.rejectedSpeed))
                return null
            }
        }
        epochAnchorMs = baseEpoch
        sampleAnchorMs = baseSample
        trusted = sample
        diagnostics = diagnostics.copy(acceptedPoints = increment(diagnostics.acceptedPoints))
        return RecordedPoint(sample.latitude, sample.longitude, normalized)
    }

    /** Legacy pure-domain callers have no platform quality metadata. */
    fun recordLegacy(accepted: Boolean, capacityAvailable: Boolean) {
        diagnostics = diagnostics.copy(
            receivedPoints = increment(diagnostics.receivedPoints),
            acceptedPoints = if (accepted) increment(diagnostics.acceptedPoints) else diagnostics.acceptedPoints,
            droppedCapacity = if (!capacityAvailable) increment(diagnostics.droppedCapacity) else diagnostics.droppedCapacity,
            rejectedTime = if (!accepted && capacityAvailable) increment(diagnostics.rejectedTime) else diagnostics.rejectedTime,
        )
    }

    companion object {
        const val MAX_ACCURACY_METERS = 50.0
        const val GAP_THRESHOLD_MS = 15_000L
        fun increment(value: Int): Int = (value + 1).coerceAtMost(DriveRecordingDiagnostics.MAX_COUNT)
    }
}
