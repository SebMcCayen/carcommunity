package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.drives.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The live-marker smoothing rules — the half of "convoy markers stop jumping"
 * that can be judged off-device.
 *
 * What the marker LOOKS like doing is a GL/Compose question and only a phone can
 * answer it. What is provable here is the decision layer: which fixes are
 * believable, how far along a glide the marker is at a given instant, and that
 * a stream of fixes moves the drawn position continuously instead of in steps.
 */
class LiveMarkerSmoothingTest {

    private val kungsbackaLat = 57.4874
    private val kungsbackaLng = 12.0757
    private val t0 = 1_700_000_000_000L

    private fun member(
        uid: String = "m1",
        latitude: Double = kungsbackaLat,
        longitude: Double = kungsbackaLng,
        updatedAtMillis: Long? = t0,
    ) = ConvoyMemberPosition(
        uid = uid,
        latitude = latitude,
        longitude = longitude,
        displayName = uid,
        imagePath = null,
        updatedAtMillis = updatedAtMillis,
    )

    // --- acceptsFix -------------------------------------------------------

    /**
     * The bug in the driver's seat: a fix a kilometre away one second after the
     * last one. No car does 3600 km/h, so the marker must not follow it.
     */
    @Test
    fun anImplausiblyFastJumpIsRejected() {
        val accepted =
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = t0,
                // ~1.1 km north.
                latitude = kungsbackaLat + 0.01,
                longitude = kungsbackaLng,
                recordedAtMillis = t0 + 1_000L,
            )
        assertFalse("a 1 km jump in one second is a GPS glitch", accepted)
    }

    /** The ordinary case: 25 m in a second (90 km/h) is just driving. */
    @Test
    fun anOrdinaryMoveIsAccepted() {
        val accepted =
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = t0,
                latitude = kungsbackaLat + 0.000225, // ~25 m north
                longitude = kungsbackaLng,
                recordedAtMillis = t0 + 1_000L,
            )
        assertTrue("90 km/h is a car, not a glitch", accepted)
    }

    /** Non-finite coordinates are not positions and can never be drawn. */
    @Test
    fun nonFiniteCoordinatesAreRejected() {
        val badLatitude =
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = t0,
                latitude = Double.NaN,
                longitude = kungsbackaLng,
                recordedAtMillis = t0 + 1_000L,
            )
        val badLongitude =
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = t0,
                latitude = kungsbackaLat,
                longitude = Double.POSITIVE_INFINITY,
                recordedAtMillis = t0 + 1_000L,
            )
        // Out of WGS-84 range is finite but still not a place on Earth.
        val outOfRange = LiveMarkerSmoothing.isDrawable(latitude = 91.0, longitude = 0.0)

        assertFalse("NaN latitude", badLatitude)
        assertFalse("infinite longitude", badLongitude)
        assertFalse("latitude beyond the pole", outOfRange)
    }

    /**
     * A first fix has nothing to be implausible RELATIVE TO, and an undated fix
     * gives no delta to divide by. Neither is evidence of a glitch, so neither
     * may freeze a member out.
     */
    @Test
    fun aFixWithNoUsableDeltaIsAccepted() {
        assertTrue(
            "no previous timestamp",
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = null,
                latitude = kungsbackaLat + 1.0,
                longitude = kungsbackaLng,
                recordedAtMillis = t0,
            ),
        )
        assertTrue(
            "no new timestamp",
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = t0,
                latitude = kungsbackaLat + 1.0,
                longitude = kungsbackaLng,
                recordedAtMillis = null,
            ),
        )
    }

    /**
     * Realtime Database re-delivers an unchanged `latest` node, and a retry can
     * land a sample out of order. Neither may drag the marker backwards.
     */
    @Test
    fun aNonAdvancingSampleIsRejected() {
        assertFalse(
            "same timestamp (a re-delivery)",
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = t0,
                latitude = kungsbackaLat + 0.0001,
                longitude = kungsbackaLng,
                recordedAtMillis = t0,
            ),
        )
        assertFalse(
            "older timestamp (out of order)",
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = t0,
                latitude = kungsbackaLat + 0.0001,
                longitude = kungsbackaLng,
                recordedAtMillis = t0 - 1_000L,
            ),
        )
    }

    /**
     * The threshold is the drives one, not a second copy. Asserted against the
     * shared constant so retuning [DriveSummary.MAX_PLAUSIBLE_SPEED_MPS] cannot
     * leave the map on an old number: a move at exactly the limit passes and one
     * just above it does not.
     */
    @Test
    fun theRejectionThresholdIsTheSharedDrivesOne() {
        val limitMps = DriveSummary.MAX_PLAUSIBLE_SPEED_MPS
        // Metres per degree of latitude, from the same Haversine the rule uses.
        val metresPerDegree =
            DriveSummary.haversineMetres(kungsbackaLat, kungsbackaLng, kungsbackaLat + 1.0, kungsbackaLng)

        fun acceptsAtSpeed(metresPerSecond: Double): Boolean =
            LiveMarkerSmoothing.acceptsFix(
                previousLatitude = kungsbackaLat,
                previousLongitude = kungsbackaLng,
                previousRecordedAtMillis = t0,
                latitude = kungsbackaLat + metresPerSecond / metresPerDegree,
                longitude = kungsbackaLng,
                recordedAtMillis = t0 + 1_000L,
            )

        assertTrue("just under the shared limit", acceptsAtSpeed(limitMps * 0.99))
        assertFalse("just over the shared limit", acceptsAtSpeed(limitMps * 1.01))
    }

    // --- interpolation ----------------------------------------------------

    /**
     * The endpoints are EXACT, not merely close: a settled marker has to sit
     * precisely on the position that was reported, or it drifts away from the
     * camera fit and the arrow geometry computed from the same numbers.
     */
    @Test
    fun interpolationEndpointsAreExact() {
        val from = 57.0
        val to = 58.25
        assertEquals(from, LiveMarkerSmoothing.lerpLatitude(from, to, 0.0), 0.0)
        assertEquals(to, LiveMarkerSmoothing.lerpLatitude(from, to, 1.0), 0.0)
        assertEquals(12.0, LiveMarkerSmoothing.lerpLongitude(12.0, 12.5, 0.0), 0.0)
        assertEquals(12.5, LiveMarkerSmoothing.lerpLongitude(12.0, 12.5, 1.0), 0.0)
        // Exact even where wrapping would otherwise normalise the answer.
        assertEquals(180.0, LiveMarkerSmoothing.lerpLongitude(179.0, 180.0, 1.0), 0.0)
    }

    /** Halfway is halfway, and the motion in between is linear. */
    @Test
    fun interpolationIsLinearBetweenTheEndpoints() {
        assertEquals(57.5, LiveMarkerSmoothing.lerpLatitude(57.0, 58.0, 0.5), 1e-12)
        assertEquals(57.25, LiveMarkerSmoothing.lerpLatitude(57.0, 58.0, 0.25), 1e-12)
        assertEquals(12.5, LiveMarkerSmoothing.lerpLongitude(12.0, 13.0, 0.5), 1e-12)
    }

    /**
     * A pair straddling the antimeridian must step the short way (0.2 degrees
     * east), not sweep 359.8 degrees back around the globe.
     */
    @Test
    fun longitudeInterpolationTakesTheShortWayRound() {
        val mid = LiveMarkerSmoothing.lerpLongitude(from = 179.9, to = -179.9, fraction = 0.5)
        assertEquals(180.0, abs(mid), 1e-9)
    }

    /** Progress is clamped at both ends and 1.0 for a zero-length glide. */
    @Test
    fun progressIsClampedToTheGlide() {
        assertEquals(0.0, LiveMarkerSmoothing.progress(-5L, 1_000L), 0.0)
        assertEquals(0.5, LiveMarkerSmoothing.progress(500L, 1_000L), 1e-12)
        assertEquals(1.0, LiveMarkerSmoothing.progress(5_000L, 1_000L), 0.0)
        assertEquals(1.0, LiveMarkerSmoothing.progress(0L, 0L), 0.0)
    }

    /**
     * The glide paces itself by how often fixes actually arrive, so it is still
     * moving when the next one lands — but a member who was gone for minutes
     * must not then crawl for minutes.
     */
    @Test
    fun theGlideFollowsTheArrivalCadenceWithinBounds() {
        assertEquals(
            LiveMarkerSmoothing.GLIDE_DEFAULT_MS,
            LiveMarkerSmoothing.glideDurationMillis(null),
        )
        assertEquals(
            LiveMarkerSmoothing.GLIDE_DEFAULT_MS,
            LiveMarkerSmoothing.glideDurationMillis(0L),
        )
        assertEquals(2_000L, LiveMarkerSmoothing.glideDurationMillis(2_000L))
        assertEquals(
            LiveMarkerSmoothing.GLIDE_MIN_MS,
            LiveMarkerSmoothing.glideDurationMillis(10L),
        )
        assertEquals(
            LiveMarkerSmoothing.GLIDE_MAX_MS,
            LiveMarkerSmoothing.glideDurationMillis(5 * 60 * 1000L),
        )
    }

    // --- LiveMarkerSmoother ----------------------------------------------

    /** A member first seen is drawn where they are — there is nothing to glide from. */
    @Test
    fun aFirstFixIsDrawnImmediately() {
        val smoother = LiveMarkerSmoother()
        val members = listOf(member())
        smoother.onPositions(members, t0)

        val drawn = smoother.rendered(members, t0).single()
        assertEquals(kungsbackaLat, drawn.latitude, 0.0)
        assertEquals(kungsbackaLng, drawn.longitude, 0.0)
        assertFalse("nothing to animate yet", smoother.isGliding(t0))
    }

    /**
     * THE behaviour Seb asked for: between two fixes the drawn position moves
     * through the gap instead of teleporting at the end of it.
     */
    @Test
    fun anAcceptedMoveIsGlidedThroughRatherThanSnapped() {
        val smoother = LiveMarkerSmoother()
        val first = listOf(member(updatedAtMillis = t0))
        smoother.onPositions(first, t0)

        // Second fix 25 m north, 1 s later, arriving 1 s after the first.
        val movedTo = kungsbackaLat + 0.000225
        val second = listOf(member(latitude = movedTo, updatedAtMillis = t0 + 1_000L))
        smoother.onPositions(second, t0 + 1_000L)

        assertTrue("a glide is running", smoother.isGliding(t0 + 1_000L))

        val atStart = smoother.rendered(second, t0 + 1_000L).single()
        assertEquals("still at the old position", kungsbackaLat, atStart.latitude, 1e-12)

        val midway = smoother.rendered(second, t0 + 1_500L).single()
        assertTrue("moved off the old position", midway.latitude > kungsbackaLat)
        assertTrue("has not arrived yet", midway.latitude < movedTo)

        val settled = smoother.rendered(second, t0 + 10_000L).single()
        assertEquals("lands exactly on the reported fix", movedTo, settled.latitude, 0.0)
        assertFalse("and stops asking for frames", smoother.isGliding(t0 + 10_000L))
    }

    /**
     * A glitch fix leaves the marker exactly where it was — it does not lurch
     * out and back, which is what the jumping looked like.
     */
    @Test
    fun aRejectedFixLeavesTheDrawnPositionAlone() {
        val smoother = LiveMarkerSmoother()
        val first = listOf(member(updatedAtMillis = t0))
        smoother.onPositions(first, t0)

        // 1 km away one second later: impossible.
        val glitch = listOf(member(latitude = kungsbackaLat + 0.01, updatedAtMillis = t0 + 1_000L))
        smoother.onPositions(glitch, t0 + 1_000L)

        val drawn = smoother.rendered(glitch, t0 + 5_000L).single()
        assertEquals("the glitch position is not drawn", kungsbackaLat, drawn.latitude, 0.0)
        assertEquals(kungsbackaLng, drawn.longitude, 0.0)
        assertFalse("and no animation was started for it", smoother.isGliding(t0 + 1_000L))
    }

    /**
     * A fix landing MID-glide continues from where the marker is being drawn, so
     * a steady stream of fixes is continuous motion rather than a rubber band
     * snapping back to the previous target each time.
     */
    @Test
    fun aFixArrivingMidGlideContinuesFromTheDrawnPosition() {
        val smoother = LiveMarkerSmoother()
        val first = listOf(member(updatedAtMillis = t0))
        smoother.onPositions(first, t0)

        val second = listOf(member(latitude = kungsbackaLat + 0.0002, updatedAtMillis = t0 + 1_000L))
        smoother.onPositions(second, t0 + 1_000L)

        // Halfway through that glide, a third fix arrives.
        val midGlide = smoother.rendered(second, t0 + 1_500L).single().latitude
        val third = listOf(member(latitude = kungsbackaLat + 0.0004, updatedAtMillis = t0 + 1_500L))
        smoother.onPositions(third, t0 + 1_500L)

        val redrawn = smoother.rendered(third, t0 + 1_500L).single()
        assertEquals("no snap on the changeover", midGlide, redrawn.latitude, 1e-12)
    }

    /**
     * Identity fields belong to the roster, not the animation: smoothing a
     * position must not rewrite who someone is or how fresh their fix is, or the
     * staleness rule and the car photo would follow the interpolated ghost.
     */
    @Test
    fun smoothingCarriesIdentityAndFreshnessThrough() {
        val smoother = LiveMarkerSmoother()
        val first = listOf(member(updatedAtMillis = t0))
        smoother.onPositions(first, t0)
        val second =
            listOf(
                member(latitude = kungsbackaLat + 0.0002, updatedAtMillis = t0 + 1_000L)
                    .copy(displayName = "Anna", imagePath = "vehicleImages/anna.jpg"),
            )
        smoother.onPositions(second, t0 + 1_000L)

        val drawn = smoother.rendered(second, t0 + 1_400L).single()
        assertEquals("m1", drawn.uid)
        assertEquals("Anna", drawn.displayName)
        assertEquals("vehicleImages/anna.jpg", drawn.imagePath)
        assertEquals(t0 + 1_000L, drawn.updatedAtMillis)
    }

    /**
     * A member whose ONLY known position is undrawable is dropped rather than
     * projected: a NaN pixel is not a marker anyone can be told about.
     */
    @Test
    fun aMemberWhoseFirstFixIsUndrawableIsNotDrawn() {
        val smoother = LiveMarkerSmoother()
        val members = listOf(member(latitude = Double.NaN))
        smoother.onPositions(members, t0)
        assertTrue(smoother.rendered(members, t0).isEmpty())
    }

    /**
     * Someone who stops sharing leaves nothing behind: the roster is the whole
     * truth, so state cannot accumulate over a long session.
     */
    @Test
    fun departedMembersArePruned() {
        val smoother = LiveMarkerSmoother()
        val both = listOf(member(uid = "a"), member(uid = "b"))
        smoother.onPositions(both, t0)

        // b moves, so b — and only b — has a glide running.
        val bothReported =
            listOf(
                member(uid = "a", updatedAtMillis = t0 + 1_000L),
                member(
                    uid = "b",
                    latitude = kungsbackaLat + 0.0002,
                    updatedAtMillis = t0 + 1_000L,
                ),
            )
        smoother.onPositions(bothReported, t0 + 1_000L)
        assertTrue("b is mid-glide", smoother.isGliding(t0 + 1_000L))

        // b stops sharing. Their track goes with them, so nothing is drawn for
        // them and — the point of the prune — b's glide can no longer be the
        // thing keeping the frame loop alive for the rest of the session.
        val onlyA = listOf(member(uid = "a", updatedAtMillis = t0 + 1_100L))
        smoother.onPositions(onlyA, t0 + 1_100L)

        assertEquals(listOf("a"), smoother.rendered(onlyA, t0 + 1_100L).map { it.uid })
        assertFalse("b's glide left with b", smoother.isGliding(t0 + 1_100L))
    }

    /**
     * A parked member on the stationary heartbeat re-reports the same
     * coordinates. That must not restart a (zero-length) glide, and it must
     * still advance the clock the NEXT move is judged against — otherwise a park
     * of several minutes would make any subsequent jump look slow enough to be
     * plausible.
     */
    @Test
    fun aStationaryHeartbeatAdvancesTheAcceptWindowWithoutAnimating() {
        val smoother = LiveMarkerSmoother()
        smoother.onPositions(listOf(member(updatedAtMillis = t0)), t0)

        // Parked for three minutes, same position.
        val heartbeatAt = t0 + 180_000L
        val heartbeat = listOf(member(updatedAtMillis = heartbeatAt))
        smoother.onPositions(heartbeat, heartbeatAt)
        assertFalse("a heartbeat in place is not motion", smoother.isGliding(heartbeatAt))

        // Then a 1 km jump one second later. Judged against the heartbeat it is
        // impossible; judged against the original fix (181 s ago) it would be a
        // believable 20 km/h and would be let through.
        val jump =
            listOf(
                member(latitude = kungsbackaLat + 0.01, updatedAtMillis = heartbeatAt + 1_000L),
            )
        smoother.onPositions(jump, heartbeatAt + 1_000L)

        val drawn = smoother.rendered(jump, heartbeatAt + 5_000L).single()
        assertEquals("the jump was still rejected", kungsbackaLat, drawn.latitude, 0.0)
    }
}
