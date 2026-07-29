package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.location.SpeedSample
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure, Android-free logic behind the live-session bar's speed readout: metres
 * per second → whole km/h, plus the two rules that decide whether there is a
 * number to show at all.
 *
 * This is a PLAIN INFORMATIONAL READOUT of the member's own current speed and
 * nothing else. There is deliberately no record, no personal best, no top-speed
 * memory, no comparison with anyone, and no colouring of "fast" versus "slow" —
 * this app does not reward, rank or score speed, and a readout that did any of
 * those would be a scoreboard rather than an instrument.
 *
 * ## Whole km/h, with a 1 km/h deadband
 * The value is rounded to a whole number: a moving vehicle's GPS speed wobbles
 * by a few tenths every fix, and a decimal place would be pure noise. Rounding
 * alone is not quite enough, though — a true speed sitting near x.5 flips the
 * rounded value back and forth on consecutive fixes. So the displayed number is
 * KEPT until the incoming reading is at least [UPDATE_THRESHOLD_KMH] away from
 * it ([displayKmh]).
 *
 * A 1 km/h deadband is the smallest that can suppress that flicker, and it was
 * chosen over a rolling average on purpose: fixes arrive about every
 * [com.kungsbackacarcommunity.app.location.BackgroundLocation.UPDATE_INTERVAL_MS]
 * (5 s), so averaging even three of them would put the readout ~10 s behind the
 * car — and a speedometer that lags is worse than one that twitches. The
 * deadband costs nothing in responsiveness instead: any real acceleration moves
 * the reading by far more than 1 km/h between two fixes (1 km/h over 5 s is
 * 0.06 m/s², an imperceptible nudge), so genuine changes are shown on the very
 * next fix while noise is absorbed.
 *
 * ## Nothing to show is shown as nothing
 * Two cases render the placeholder rather than a number, because `0` would be a
 * lie in both: no fix has carried a speed yet, and the stream has gone quiet
 * (tunnel, lost signal, sharing running without a GPS source). Standing still,
 * by contrast, genuinely IS `0 km/h` and is shown as such.
 */
object LiveSpeedReadout {
    /** Metres per second → kilometres per hour. */
    const val MPS_TO_KMH: Double = 3.6

    /**
     * How far the incoming reading must be from the displayed number before the
     * display follows it, in km/h. See the class KDoc for why this is a deadband
     * rather than an average.
     */
    const val UPDATE_THRESHOLD_KMH: Double = 1.0

    /**
     * How old a reading may be before it stops being shown, in milliseconds.
     *
     * Three missed fixes at the fused provider's 5 s cadence. Long enough that a
     * single dropped update never blanks the readout, short enough that driving
     * into a tunnel does not leave a confident "90" frozen on screen while the
     * car slows to a halt.
     */
    const val STALE_AFTER_MS: Long = 15_000L

    /**
     * Metres per second → whole km/h, or null when there is nothing to convert.
     * Null, negative and non-finite inputs all yield null so the caller renders
     * the placeholder instead of a fabricated `0`.
     */
    fun kmhOrNull(metersPerSecond: Double?): Int? {
        if (metersPerSecond == null) return null
        if (!metersPerSecond.isFinite() || metersPerSecond < 0.0) return null
        return (metersPerSecond * MPS_TO_KMH).roundToInt()
    }

    /** Whether [sample] is recent enough to still describe the current speed. */
    fun isFresh(sample: SpeedSample?, nowMillis: Long): Boolean {
        if (sample == null) return false
        val age = nowMillis - sample.atMillis
        // A negative age is a clock that moved backwards between the fix and the
        // read; treat it as fresh rather than blanking a perfectly good reading.
        return age < STALE_AFTER_MS
    }

    /**
     * The number the bar should display, given the latest [sample], the current
     * clock reading and whatever is on screen right now ([shownKmh], null when
     * the placeholder is showing).
     *
     * Returns null for "show the placeholder" — no reading, or a stale one. When
     * it does return a number the caller must feed it back as [shownKmh] on the
     * next call; that is what makes the deadband hold, and feeding back null
     * after a stale gap correctly lets the next fix set the display outright.
     */
    fun displayKmh(sample: SpeedSample?, nowMillis: Long, shownKmh: Int?): Int? {
        if (!isFresh(sample, nowMillis)) return null
        val metersPerSecond = sample?.metersPerSecond ?: return null
        if (!metersPerSecond.isFinite() || metersPerSecond < 0.0) return null
        val kmh = metersPerSecond * MPS_TO_KMH
        // Nothing on screen yet: adopt the reading immediately, so the very first
        // fix of a session is not held back by a deadband it has nothing to
        // measure against.
        if (shownKmh == null) return kmh.roundToInt()
        if (abs(kmh - shownKmh) < UPDATE_THRESHOLD_KMH) return shownKmh
        return kmh.roundToInt()
    }
}
