package com.kungsbackacarcommunity.app.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One ground-speed reading taken straight off a fused-location fix.
 *
 * @property metersPerSecond the platform's own `Location.speed`, never negative
 *   and always finite (see [CurrentSpeed.onFix], which is the only writer).
 * @property atMillis the DEVICE clock instant the fix carrying it arrived —
 *   deliberately not `Location.time`, which is the GPS clock and can disagree
 *   with the wall clock a reader would compare it against. Consumers use it to
 *   decide whether a reading is still fresh enough to show.
 */
data class SpeedSample(
    val metersPerSecond: Double,
    val atMillis: Long,
)

/**
 * Process-scoped holder for the device's most recent ground speed, published by
 * whichever fix stream is running and read by the map's live-session bar.
 *
 * ## Why a holder rather than a field on the recording state
 * The speed is a pure UI readout: it is never persisted, never uploaded, and
 * never enters any drive's stored stats (the backend deliberately stores only
 * average speed, and this app does not rank, score or reward speed at all). It
 * therefore has no business inside [com.kungsbackacarcommunity.app.drives.RecordingState],
 * which is the shape of a drive that gets SAVED. Keeping it here also gives it
 * the right lifetime: process-scoped, like
 * [com.kungsbackacarcommunity.app.drives.SingleSessionRecording] itself, so an
 * Activity recreation mid-session does not blank the readout.
 *
 * ## Why `Location.speed` and not a position delta
 * The fused provider's speed is derived inside the platform from the GNSS
 * Doppler shift where available, which is both more accurate and far steadier
 * than dividing two positions by their time difference — the latter turns every
 * metre of positional noise into several km/h of readout jitter. Fixes that
 * carry no speed at all (`hasSpeed()` false) are simply not published, so the
 * last good reading stands until it goes stale rather than flickering to a
 * placeholder for one fix.
 *
 * Not thread-safe by construction beyond the [MutableStateFlow] itself; writes
 * arrive on the main looper (the fused-location callback) and reads happen in
 * composition.
 */
object CurrentSpeed {
    private val sampleState = MutableStateFlow<SpeedSample?>(null)

    /** The latest usable reading, or null when none has arrived yet. */
    val sample: StateFlow<SpeedSample?> = sampleState.asStateFlow()

    /**
     * Publishes the speed carried by a fix.
     *
     * [speedMps] is null when the fix carried no speed (`hasSpeed()` false), and
     * such a fix is IGNORED rather than clearing the reading: a single
     * speed-less fix in an otherwise healthy stream should not blank the
     * readout. A missing stream is handled by staleness at the reader instead.
     * Negative and non-finite values are rejected for the same reason — they are
     * provider noise, not a real "we stopped".
     */
    fun onFix(speedMps: Double?, nowMillis: Long) {
        val speed = speedMps ?: return
        if (!speed.isFinite() || speed < 0.0) return
        sampleState.value = SpeedSample(metersPerSecond = speed, atMillis = nowMillis)
    }

    /**
     * Drops the reading, so a new session never opens on the previous one's
     * number. Called when a live session starts and when it ends.
     */
    fun clear() {
        sampleState.value = null
    }
}
