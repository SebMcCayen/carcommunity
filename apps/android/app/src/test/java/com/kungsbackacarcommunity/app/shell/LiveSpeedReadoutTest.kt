package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.location.CurrentSpeed
import com.kungsbackacarcommunity.app.location.SpeedSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSpeedReadoutTest {
    @After
    fun tearDown() {
        // CurrentSpeed is a process-scoped singleton; leave it empty for the next
        // test file (this module's unit tests share one JVM).
        CurrentSpeed.clear()
    }

    // --- m/s -> whole km/h ------------------------------------------------

    @Test
    fun metresPerSecond_convertToWholeKmh() {
        assertEquals(0, LiveSpeedReadout.kmhOrNull(0.0))
        // 3.6 m/s is exactly 13 km/h; 25 m/s is exactly 90.
        assertEquals(13, LiveSpeedReadout.kmhOrNull(3.6))
        assertEquals(90, LiveSpeedReadout.kmhOrNull(25.0))
        // 13.8889 m/s = 50.0 km/h (the Swedish urban limit).
        assertEquals(50, LiveSpeedReadout.kmhOrNull(13.8889))
    }

    @Test
    fun conversion_roundsRatherThanTruncates() {
        // 15.0 m/s = 54.0 km/h exactly.
        assertEquals(54, LiveSpeedReadout.kmhOrNull(15.0))
        // 15.2 m/s = 54.72 -> 55, not 54.
        assertEquals(55, LiveSpeedReadout.kmhOrNull(15.2))
        // 15.1 m/s = 54.36 -> 54.
        assertEquals(54, LiveSpeedReadout.kmhOrNull(15.1))
    }

    @Test
    fun absentOrNonsensicalSpeeds_yieldNoNumber() {
        assertNull(LiveSpeedReadout.kmhOrNull(null))
        assertNull(LiveSpeedReadout.kmhOrNull(-1.0))
        assertNull(LiveSpeedReadout.kmhOrNull(Double.NaN))
        assertNull(LiveSpeedReadout.kmhOrNull(Double.POSITIVE_INFINITY))
        assertNull(LiveSpeedReadout.kmhOrNull(Double.NEGATIVE_INFINITY))
    }

    // --- freshness --------------------------------------------------------

    @Test
    fun noSampleIsNeverFresh() {
        assertFalse(LiveSpeedReadout.isFresh(null, 1_000L))
    }

    @Test
    fun sampleGoesStaleAfterTheWindow() {
        val sample = SpeedSample(metersPerSecond = 20.0, atMillis = 1_000L)
        assertTrue(LiveSpeedReadout.isFresh(sample, 1_000L))
        assertTrue(
            LiveSpeedReadout.isFresh(sample, 1_000L + LiveSpeedReadout.STALE_AFTER_MS - 1L),
        )
        assertFalse(LiveSpeedReadout.isFresh(sample, 1_000L + LiveSpeedReadout.STALE_AFTER_MS))
    }

    @Test
    fun clockMovingBackwardsDoesNotBlankAGoodReading() {
        val sample = SpeedSample(metersPerSecond = 20.0, atMillis = 5_000L)
        assertTrue(LiveSpeedReadout.isFresh(sample, 4_000L))
    }

    // --- the displayed number --------------------------------------------

    @Test
    fun noFixYet_showsNoNumberRatherThanZero() {
        assertNull(LiveSpeedReadout.displayKmh(sample = null, nowMillis = 1_000L, shownKmh = null))
        // Even with a number already on screen, "no sample" must blank it rather
        // than freeze the old value forever.
        assertNull(LiveSpeedReadout.displayKmh(sample = null, nowMillis = 1_000L, shownKmh = 72))
    }

    @Test
    fun staleFix_fallsBackToNoNumber() {
        val sample = SpeedSample(metersPerSecond = 25.0, atMillis = 0L)
        assertEquals(90, LiveSpeedReadout.displayKmh(sample, nowMillis = 0L, shownKmh = null))
        assertNull(
            LiveSpeedReadout.displayKmh(
                sample,
                nowMillis = LiveSpeedReadout.STALE_AFTER_MS,
                shownKmh = 90,
            ),
        )
    }

    @Test
    fun stationary_showsZeroBecauseZeroIsTrue() {
        val sample = SpeedSample(metersPerSecond = 0.0, atMillis = 0L)
        assertEquals(0, LiveSpeedReadout.displayKmh(sample, nowMillis = 0L, shownKmh = null))
    }

    @Test
    fun firstReadingIsAdoptedImmediately() {
        // Nothing on screen: no deadband to measure against, so the very first fix
        // must show at once rather than wait for a second one.
        val sample = SpeedSample(metersPerSecond = 15.0, atMillis = 0L)
        assertEquals(54, LiveSpeedReadout.displayKmh(sample, nowMillis = 0L, shownKmh = null))
    }

    @Test
    fun readingsWithinTheDeadbandDoNotMoveTheDisplay() {
        // 54 km/h is on screen; a reading 0.9 km/h away must not disturb it, even
        // though it would round to 55 on its own.
        val jitterUp = SpeedSample(metersPerSecond = 54.9 / 3.6, atMillis = 0L)
        assertEquals(54, LiveSpeedReadout.displayKmh(jitterUp, nowMillis = 0L, shownKmh = 54))
        val jitterDown = SpeedSample(metersPerSecond = 53.2 / 3.6, atMillis = 0L)
        assertEquals(54, LiveSpeedReadout.displayKmh(jitterDown, nowMillis = 0L, shownKmh = 54))
    }

    @Test
    fun aReadingBeyondTheThresholdMovesTheDisplay() {
        // Just past the deadband in either direction. Deliberately 1.1 km/h rather
        // than exactly 1.0: the m/s round trip through 3.6 is not bit-exact, and a
        // test pinned to the boundary would assert floating-point noise rather
        // than the rule.
        val faster = SpeedSample(metersPerSecond = 55.1 / 3.6, atMillis = 0L)
        assertEquals(55, LiveSpeedReadout.displayKmh(faster, nowMillis = 0L, shownKmh = 54))
        val slower = SpeedSample(metersPerSecond = 52.9 / 3.6, atMillis = 0L)
        assertEquals(53, LiveSpeedReadout.displayKmh(slower, nowMillis = 0L, shownKmh = 54))
    }

    @Test
    fun realAccelerationIsFollowedOnTheVeryNextReading() {
        // The deadband must not make the readout laggy: a single fix's worth of
        // ordinary acceleration is shown immediately, not averaged away.
        val sample = SpeedSample(metersPerSecond = 80.0 / 3.6, atMillis = 0L)
        assertEquals(80, LiveSpeedReadout.displayKmh(sample, nowMillis = 0L, shownKmh = 50))
    }

    @Test
    fun theDisplayIsStableWhenFedBackToItself() {
        // Feeding the returned value back in with the SAME sample must not move it
        // again — otherwise the readout could oscillate between two numbers.
        val sample = SpeedSample(metersPerSecond = 53.7 / 3.6, atMillis = 0L)
        val first = LiveSpeedReadout.displayKmh(sample, nowMillis = 0L, shownKmh = null)
        assertEquals(54, first)
        assertEquals(54, LiveSpeedReadout.displayKmh(sample, nowMillis = 0L, shownKmh = first))
    }

    @Test
    fun jitterAroundAHalfDoesNotFlipTheNumberBackAndForth() {
        // The case plain rounding gets wrong: a true speed sitting on x.5.
        val low = SpeedSample(metersPerSecond = 49.4 / 3.6, atMillis = 0L)
        val high = SpeedSample(metersPerSecond = 49.6 / 3.6, atMillis = 0L)
        var shown = LiveSpeedReadout.displayKmh(low, nowMillis = 0L, shownKmh = null)
        assertEquals(49, shown)
        repeat(5) {
            shown = LiveSpeedReadout.displayKmh(high, nowMillis = 0L, shownKmh = shown)
            assertEquals(49, shown)
            shown = LiveSpeedReadout.displayKmh(low, nowMillis = 0L, shownKmh = shown)
            assertEquals(49, shown)
        }
    }

    @Test
    fun aStaleGapLetsTheNextReadingSetTheDisplayOutright() {
        // After the placeholder, there is no value to measure a deadband against,
        // so a reading well inside the threshold still shows.
        val sample = SpeedSample(metersPerSecond = 54.4 / 3.6, atMillis = 100L)
        assertEquals(54, LiveSpeedReadout.displayKmh(sample, nowMillis = 100L, shownKmh = null))
    }

    @Test
    fun nonFiniteSampleIsNotDisplayed() {
        val sample = SpeedSample(metersPerSecond = Double.NaN, atMillis = 0L)
        assertNull(LiveSpeedReadout.displayKmh(sample, nowMillis = 0L, shownKmh = 30))
    }

    // --- the holder that feeds it ----------------------------------------

    @Test
    fun holderPublishesOnlyUsableReadings() {
        CurrentSpeed.clear()
        assertNull(CurrentSpeed.sample.value)

        CurrentSpeed.onFix(speedMps = 15.0, nowMillis = 1_000L)
        assertEquals(SpeedSample(15.0, 1_000L), CurrentSpeed.sample.value)

        // A fix without a speed must NOT wipe the last good reading: one speedless
        // fix in a healthy stream should not flicker the bar to a placeholder.
        CurrentSpeed.onFix(speedMps = null, nowMillis = 2_000L)
        assertEquals(SpeedSample(15.0, 1_000L), CurrentSpeed.sample.value)

        // Nor should provider noise.
        CurrentSpeed.onFix(speedMps = -3.0, nowMillis = 3_000L)
        CurrentSpeed.onFix(speedMps = Double.NaN, nowMillis = 3_000L)
        CurrentSpeed.onFix(speedMps = Double.POSITIVE_INFINITY, nowMillis = 3_000L)
        assertEquals(SpeedSample(15.0, 1_000L), CurrentSpeed.sample.value)

        CurrentSpeed.onFix(speedMps = 0.0, nowMillis = 4_000L)
        assertEquals(SpeedSample(0.0, 4_000L), CurrentSpeed.sample.value)

        CurrentSpeed.clear()
        assertNull(CurrentSpeed.sample.value)
    }
}
