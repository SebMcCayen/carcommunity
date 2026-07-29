package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.drives.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accept/reject decision behind "my position was jumping around on the
 * screen, even if I was standing still — 1-2 km jumps".
 *
 * Every case below is stated in the units of the bug report (metres of error,
 * seconds between fixes) rather than in the units of the implementation, so a
 * failure says which real-world situation broke rather than which branch did.
 */
class LivePositionQualityTest {

    private val lat = 57.4874
    private val lng = 12.0757
    private val t0 = 1_700_000_000_000L

    /** Metres per degree of latitude here, from the same Haversine the rules use. */
    private val metresPerDegree = DriveSummary.haversineMetres(lat, lng, lat + 1.0, lng)

    /** A latitude [metres] north of the anchor. */
    private fun north(metres: Double): Double = lat + metres / metresPerDegree

    private fun incoming(
        northMetres: Double,
        deltaMillis: Long?,
        accuracyMeters: Double? = null,
        pendingNorthMetres: Double? = null,
    ): LiveFixVerdict =
        LivePositionQuality.judgeIncoming(
            previousLatitude = lat,
            previousLongitude = lng,
            previousRecordedAtMillis = if (deltaMillis == null) null else t0,
            pendingLatitude = pendingNorthMetres?.let { north(it) },
            pendingLongitude = pendingNorthMetres?.let { lng },
            latitude = north(northMetres),
            longitude = lng,
            recordedAtMillis = deltaMillis?.let { t0 + it },
            accuracyMeters = accuracyMeters,
        )

    private fun publish(
        northMetres: Double,
        deltaMillis: Long?,
        accuracyMeters: Double? = null,
    ): LiveFixVerdict =
        LivePositionQuality.judgePublish(
            previousLatitude = lat,
            previousLongitude = lng,
            previousAtMillis = deltaMillis?.let { t0 },
            latitude = north(northMetres),
            longitude = lng,
            atMillis = t0 + (deltaMillis ?: 0L),
            accuracyMeters = accuracyMeters,
        )

    // --- Seb's case -------------------------------------------------------

    /**
     * THE BUG. A stationary phone, a 1.6 km error, reported accuracy 1200 m.
     *
     * The consumer must reject it, and it must reject it on ACCURACY — the
     * signal that is actually diagnostic — not by accident on some other rule.
     */
    @Test
    fun aStationaryKilometreJumpWithPoorAccuracyIsRejected() {
        assertEquals(
            LiveFixVerdict.REJECT_ACCURACY,
            incoming(northMetres = 1_600.0, deltaMillis = 180_000L, accuracyMeters = 1_200.0),
        )
    }

    /**
     * THE GAP THE OLD FILTER LEFT, stated numerically.
     *
     * The pre-existing rule was speed-only: distance ÷ elapsed time against
     * [DriveSummary.MAX_PLAUSIBLE_SPEED_MPS]. A parked publisher writes on the
     * 3-minute stationary heartbeat, so the elapsed time is 180 s and a 1.6 km
     * jump implies ~9 m/s — nowhere near the ~55.6 m/s limit. This test asserts
     * BOTH halves: that the speed rule really would have let it through, and
     * that the new rules stop it anyway.
     *
     * If this ever fails on the first assertion, the premise of the whole fix
     * has changed and the rest should be re-derived rather than patched.
     */
    @Test
    fun theSpeedRuleAloneWouldHaveLetSebsJumpThrough() {
        val impliedSpeed = LivePositionQuality.impliedSpeedMps(1_600.0, 180_000L)!!
        assertTrue(
            "1.6 km over 3 min is only ${impliedSpeed.toInt()} m/s — plausible for a car",
            impliedSpeed < DriveSummary.MAX_PLAUSIBLE_SPEED_MPS,
        )
        assertFalse(
            "so the speed rule alone does not fire",
            LivePositionQuality.isImplausibleSpeed(1_600.0, 180_000L),
        )
        // With accuracy, it is rejected outright; without it, it is held for
        // corroboration. Either way the marker does not move on this sample.
        assertEquals(
            LiveFixVerdict.REJECT_ACCURACY,
            incoming(1_600.0, 180_000L, accuracyMeters = 1_200.0),
        )
        assertEquals(LiveFixVerdict.HOLD_UNCORROBORATED, incoming(1_600.0, 180_000L))
    }

    /**
     * The same jump across a much LONGER gap — ten minutes, where the implied
     * speed falls to ~2.7 m/s (walking pace) and the speed rule is even further
     * from firing. Elapsed time must not be able to launder a bad fix.
     */
    @Test
    fun aLongTimeGapDoesNotLaunderTheSameJump() {
        assertFalse(
            "ten minutes makes it look like a stroll",
            LivePositionQuality.isImplausibleSpeed(1_600.0, 600_000L),
        )
        assertEquals(
            LiveFixVerdict.REJECT_ACCURACY,
            incoming(1_600.0, 600_000L, accuracyMeters = 1_200.0),
        )
        assertEquals(LiveFixVerdict.HOLD_UNCORROBORATED, incoming(1_600.0, 600_000L))
    }

    // --- legitimate movement must survive ---------------------------------

    /**
     * Motorway driving: 110 km/h is ~153 m per 5-second publish cadence, with a
     * good fix behind it. Nothing about it may be filtered.
     */
    @Test
    fun motorwayDrivingIsAccepted() {
        assertEquals(
            LiveFixVerdict.ACCEPT,
            incoming(northMetres = 153.0, deltaMillis = 5_000L, accuracyMeters = 7.0),
        )
        assertEquals(
            LiveFixVerdict.ACCEPT,
            publish(northMetres = 153.0, deltaMillis = 5_000L, accuracyMeters = 7.0),
        )
    }

    /**
     * Even at 200 km/h — the very top of what the shared constant calls a car —
     * a 5-second step is ~278 m, still under the corroboration trigger. The
     * corroboration path must be unreachable by driving.
     */
    @Test
    fun evenTheFastestPlausibleDrivingNeverNeedsCorroboration() {
        val metresIn5s = DriveSummary.MAX_PLAUSIBLE_SPEED_MPS * 5.0
        assertTrue(
            "the trigger sits above the fastest plausible 5 s step",
            metresIn5s < LivePositionQuality.CORROBORATION_TRIGGER_METERS,
        )
        assertEquals(LiveFixVerdict.ACCEPT, incoming(metresIn5s, 5_000L, accuracyMeters = 10.0))
    }

    /**
     * A genuinely resumed session: five minutes out of contact, then 4 km
     * further on (48 km/h) with a good fix. Accepted immediately — a trustworthy
     * fix vouches for its own displacement, so a real journey is not delayed.
     */
    @Test
    fun aGenuineLongMoveWithAGoodFixIsAcceptedImmediately() {
        assertEquals(
            LiveFixVerdict.ACCEPT,
            incoming(northMetres = 4_000.0, deltaMillis = 300_000L, accuracyMeters = 9.0),
        )
    }

    /**
     * The same journey reported by a publisher that sends NO accuracy: held for
     * one fix, then accepted as soon as a second fix agrees. The cost of the
     * guard to an honest member is one sample, never a permanent freeze-out.
     */
    @Test
    fun aGenuineLongMoveWithoutAccuracyCostsExactlyOneFix() {
        assertEquals(
            LiveFixVerdict.HOLD_UNCORROBORATED,
            incoming(northMetres = 4_000.0, deltaMillis = 300_000L),
        )
        assertEquals(
            LiveFixVerdict.ACCEPT,
            incoming(
                northMetres = 4_050.0,
                deltaMillis = 305_000L,
                pendingNorthMetres = 4_000.0,
            ),
        )
    }

    /**
     * A held candidate only corroborates fixes that AGREE with it. A second
     * outlier somewhere else entirely replaces the candidate rather than
     * confirming it, or the rule would be a two-step teleport instead of a
     * guard.
     */
    @Test
    fun aHeldCandidateDoesNotCorroborateADifferentJump() {
        assertEquals(
            LiveFixVerdict.HOLD_UNCORROBORATED,
            incoming(northMetres = 4_000.0, deltaMillis = 300_000L, pendingNorthMetres = 1_600.0),
        )
    }

    // --- unknown is not bad -----------------------------------------------

    /**
     * The rollout guarantee: a fix with no accuracy at all — an older publisher,
     * or a provider that omits it — must not be blanket-rejected, or every such
     * member disappears from the map the day this ships.
     */
    @Test
    fun missingAccuracyIsUnknownNotBad() {
        assertTrue("null accuracy is usable", LivePositionQuality.isUsableAccuracy(null))
        assertFalse("but not trusted", LivePositionQuality.isTrustedAccuracy(null))
        assertEquals(
            LiveFixVerdict.ACCEPT,
            incoming(northMetres = 100.0, deltaMillis = 5_000L, accuracyMeters = null),
        )
        assertEquals(
            LiveFixVerdict.ACCEPT,
            publish(northMetres = 100.0, deltaMillis = 5_000L, accuracyMeters = null),
        )
    }

    /** A NaN, infinite or negative accuracy is not a measurement — it is unknown. */
    @Test
    fun nonsensicalAccuracyIsFoldedIntoUnknown() {
        assertNull(LivePositionQuality.normalizedAccuracy(Double.NaN))
        assertNull(LivePositionQuality.normalizedAccuracy(Double.POSITIVE_INFINITY))
        assertNull(LivePositionQuality.normalizedAccuracy(-1.0))
        assertEquals(
            LiveFixVerdict.ACCEPT,
            incoming(northMetres = 100.0, deltaMillis = 5_000L, accuracyMeters = Double.NaN),
        )
    }

    // --- thresholds and boundaries ----------------------------------------

    /** The accuracy gate is a boundary, and the boundary is inclusive. */
    @Test
    fun theAccuracyGateIsInclusiveAtItsThreshold() {
        val max = LivePositionQuality.MAX_USABLE_ACCURACY_METERS
        assertTrue(LivePositionQuality.isUsableAccuracy(max))
        assertFalse(LivePositionQuality.isUsableAccuracy(max + 0.1))
        val trusted = LivePositionQuality.TRUSTED_ACCURACY_METERS
        assertTrue(LivePositionQuality.isTrustedAccuracy(trusted))
        assertFalse(LivePositionQuality.isTrustedAccuracy(trusted + 0.1))
    }

    /**
     * The speed threshold is the SHARED drives one, not a second copy: asserted
     * against the constant so retuning it moves the publisher, the map and the
     * drive scans together.
     */
    @Test
    fun theSpeedThresholdIsTheSharedDrivesOne() {
        val limit = DriveSummary.MAX_PLAUSIBLE_SPEED_MPS
        assertFalse(LivePositionQuality.isImplausibleSpeed(limit * 0.99, 1_000L))
        assertTrue(LivePositionQuality.isImplausibleSpeed(limit * 1.01, 1_000L))
        assertEquals(
            LiveFixVerdict.REJECT_SPEED,
            incoming(northMetres = limit * 1.5, deltaMillis = 1_000L, accuracyMeters = 5.0),
        )
        assertEquals(
            LiveFixVerdict.REJECT_SPEED,
            publish(northMetres = limit * 1.5, deltaMillis = 1_000L, accuracyMeters = 5.0),
        )
    }

    /**
     * The publisher's advantage, stated as a number: the identical constant is
     * ~36x tighter against its own 5-second observation cadence than against the
     * 3-minute heartbeat a viewer sees. This is why the gate belongs at source.
     */
    @Test
    fun theSameSpeedRuleIsFarSharperAtThePublisher() {
        val limit = DriveSummary.MAX_PLAUSIBLE_SPEED_MPS
        val publisherCeiling = limit * (BackgroundLocation.UPDATE_INTERVAL_MS / 1000.0)
        val viewerCeiling = limit * (BackgroundLocation.STATIONARY_HEARTBEAT_MS / 1000.0)
        assertTrue("a 1.6 km jump is impossible at the 5 s cadence", publisherCeiling < 1_600.0)
        assertTrue("but plausible at the 3 min one", viewerCeiling > 1_600.0)
        assertEquals(
            LiveFixVerdict.REJECT_SPEED,
            publish(northMetres = 1_600.0, deltaMillis = BackgroundLocation.UPDATE_INTERVAL_MS),
        )
    }

    // --- degenerate input --------------------------------------------------

    /** Non-finite and out-of-range coordinates are not positions, on either side. */
    @Test
    fun nonFiniteCoordinatesAreRejected() {
        assertFalse(LivePositionQuality.isDrawable(Double.NaN, lng))
        assertFalse(LivePositionQuality.isDrawable(lat, Double.POSITIVE_INFINITY))
        assertFalse(LivePositionQuality.isDrawable(91.0, 0.0))
        assertFalse(LivePositionQuality.isDrawable(0.0, 181.0))
        assertEquals(
            LiveFixVerdict.REJECT_UNDRAWABLE,
            LivePositionQuality.judgeIncoming(
                previousLatitude = lat,
                previousLongitude = lng,
                previousRecordedAtMillis = t0,
                pendingLatitude = null,
                pendingLongitude = null,
                latitude = Double.NaN,
                longitude = lng,
                recordedAtMillis = t0 + 1_000L,
                accuracyMeters = 5.0,
            ),
        )
        assertEquals(
            LiveFixVerdict.REJECT_UNDRAWABLE,
            LivePositionQuality.judgePublish(
                previousLatitude = lat,
                previousLongitude = lng,
                previousAtMillis = t0,
                latitude = lat,
                longitude = Double.NEGATIVE_INFINITY,
                atMillis = t0 + 1_000L,
                accuracyMeters = 5.0,
            ),
        )
    }

    /**
     * A repeated or out-of-order sample must not drag a marker backwards through
     * time — Realtime Database re-delivers an unchanged `latest` node.
     */
    @Test
    fun aNonAdvancingSampleIsRejectedOnTheConsumer() {
        assertEquals(LiveFixVerdict.REJECT_NOT_NEWER, incoming(10.0, 0L))
        assertEquals(LiveFixVerdict.REJECT_NOT_NEWER, incoming(10.0, -1_000L))
    }

    /**
     * The publisher deliberately does NOT reject a non-advancing fix: its
     * timestamps come from the platform, and a clock correction that moved one
     * backwards would otherwise pin the anchor in the future and end sharing
     * silently and permanently. Duplicates are the movement/heartbeat throttle's
     * job ([BackgroundLocation.shouldPublish]).
     */
    @Test
    fun thePublisherTreatsABackwardsClockAsUnknownNotAsAGlitch() {
        assertEquals(
            LiveFixVerdict.ACCEPT,
            LivePositionQuality.judgePublish(
                previousLatitude = lat,
                previousLongitude = lng,
                previousAtMillis = t0,
                latitude = north(10.0),
                longitude = lng,
                atMillis = t0 - 60_000L,
                accuracyMeters = 5.0,
            ),
        )
        assertNull("an unknown interval yields no speed", LivePositionQuality.impliedSpeedMps(10.0, -1L))
        assertNull(LivePositionQuality.impliedSpeedMps(10.0, null))
        assertFalse(
            "and an unknown interval is never 'implausible'",
            LivePositionQuality.isImplausibleSpeed(10_000.0, null),
        )
    }

    /** With no previous fix at all there is nothing to compare against. */
    @Test
    fun theFirstFixOfARunIsAccepted() {
        assertEquals(
            LiveFixVerdict.ACCEPT,
            LivePositionQuality.judgePublish(
                previousLatitude = null,
                previousLongitude = null,
                previousAtMillis = null,
                latitude = lat,
                longitude = lng,
                atMillis = t0,
                accuracyMeters = 12.0,
            ),
        )
    }

    /** ...unless it is itself a fix we would not draw. */
    @Test
    fun aFirstFixThatIsTooCoarseIsStillRejected() {
        assertEquals(
            LiveFixVerdict.REJECT_ACCURACY,
            LivePositionQuality.judgePublish(
                previousLatitude = null,
                previousLongitude = null,
                previousAtMillis = null,
                latitude = lat,
                longitude = lng,
                atMillis = t0,
                accuracyMeters = 2_500.0,
            ),
        )
    }
}
