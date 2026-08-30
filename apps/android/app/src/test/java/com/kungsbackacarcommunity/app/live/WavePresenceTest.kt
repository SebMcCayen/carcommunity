package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure client decisions for the wave-to-nearby control: WHEN the icon is
 * shown (sharing + someone nearby), and the client cooldown gate that greys it.
 * The server is the real anti-spam authority; these guard the UX mirror.
 */
class WavePresenceTest {
    // --- visibility ---------------------------------------------------------

    @Test
    fun `wave control is shown only while sharing AND someone is nearby`() {
        assertTrue(WavePresence.isWaveControlVisible(isSharingLive = true, waveableInRangeCount = 1))
        assertTrue(WavePresence.isWaveControlVisible(isSharingLive = true, waveableInRangeCount = 7))
    }

    @Test
    fun `wave control is hidden when nobody is nearby`() {
        assertFalse(WavePresence.isWaveControlVisible(isSharingLive = true, waveableInRangeCount = 0))
    }

    @Test
    fun `wave control is hidden when not sharing, even with people nearby`() {
        assertFalse(WavePresence.isWaveControlVisible(isSharingLive = false, waveableInRangeCount = 3))
        assertFalse(WavePresence.isWaveControlVisible(isSharingLive = false, waveableInRangeCount = 0))
    }

    // --- cooldown gate ------------------------------------------------------

    @Test
    fun `send is enabled once now reaches the cooldown deadline`() {
        assertFalse(WavePresence.isSendEnabled(nowMs = 1_000, cooldownUntilMs = 5_000))
        // Exactly at the deadline is enabled (inclusive).
        assertTrue(WavePresence.isSendEnabled(nowMs = 5_000, cooldownUntilMs = 5_000))
        assertTrue(WavePresence.isSendEnabled(nowMs = 6_000, cooldownUntilMs = 5_000))
    }

    @Test
    fun `a zero deadline (never waved) is always enabled`() {
        assertTrue(WavePresence.isSendEnabled(nowMs = 0, cooldownUntilMs = 0))
    }

    @Test
    fun `cooldownUntil adds the window to now and mirrors the server default`() {
        assertEquals(1_045_000L, WavePresence.cooldownUntil(nowMs = 1_000_000L))
        assertEquals(WAVE_COOLDOWN_MS, WavePresence.cooldownUntil(nowMs = 0L))
        assertEquals(5_500L, WavePresence.cooldownUntil(nowMs = 500L, windowMs = 5_000L))
    }

    // --- own-position wave eligibility (#1039) ------------------------------
    //
    // The bug: the wave control lit up for a member the map camera had been
    // zoomed to, even hundreds of km away, because visibility used the
    // camera-centred roster. Delivery is bounded server-side to sharers within
    // WAVE_RADIUS_METERS of the SENDER'S own position; these pin the matching
    // client bound. Markers are placed due NORTH of the own point (same
    // longitude) so their great-circle distance is exactly R * Δlat — a clean,
    // camera-independent way to sit them a chosen number of metres away.

    private val ownLat = 0.0
    private val ownLng = 0.0

    /** A live marker [metersNorth] due north of the own point (same longitude). */
    private fun markerNorth(uid: String, metersNorth: Double): LiveMarker =
        LiveMarker(
            uid = uid,
            latitude = Math.toDegrees(metersNorth / ConvoyEdgeGeometry.EARTH_RADIUS_METERS),
            longitude = ownLng,
        )

    private fun sessionNorth(uid: String, metersNorth: Double): NearbyLiveSession =
        NearbyLiveSession(
            uid = uid,
            latitude = Math.toDegrees(metersNorth / ConvoyEdgeGeometry.EARTH_RADIUS_METERS),
            longitude = ownLng,
        )

    private fun eligibleUids(vararg markers: LiveMarker): List<String> =
        WavePresence.waveEligibleInRange(ownLat, ownLng, markers.toList()).map { it.uid }

    @Test
    fun `a marker well within 15 km is wave-eligible`() {
        assertEquals(listOf("near"), eligibleUids(markerNorth("near", 5_000.0)))
    }

    @Test
    fun `a Norway-to-Sweden-scale marker is NOT wave-eligible`() {
        // ~300 km away — the reported "wave from Norway to Sweden" case.
        assertTrue(eligibleUids(markerNorth("faraway", 300_000.0)).isEmpty())
    }

    @Test
    fun `the 15 km edge admits just-under and rejects just-over`() {
        assertEquals(listOf("under"), eligibleUids(markerNorth("under", 14_999.0)))
        assertTrue(eligibleUids(markerNorth("over", 15_001.0)).isEmpty())
    }

    @Test
    fun `the boundary is inclusive — exactly at the radius still counts`() {
        // 0 <= radius: a marker on the own point is eligible even at radius 0,
        // pinning the `<=` (not `<`) semantics deterministically.
        val onOwnPoint = LiveMarker(uid = "self", latitude = ownLat, longitude = ownLng)
        assertEquals(
            listOf("self"),
            WavePresence.waveEligibleInRange(ownLat, ownLng, listOf(onOwnPoint), radiusMeters = 0.0)
                .map { it.uid },
        )
    }

    @Test
    fun `a null own position yields no eligible markers`() {
        val markers = listOf(markerNorth("near", 1_000.0))
        assertTrue(WavePresence.waveEligibleInRange(null, ownLng, markers).isEmpty())
        assertTrue(WavePresence.waveEligibleInRange(ownLat, null, markers).isEmpty())
        assertTrue(WavePresence.waveEligibleInRange(null, null, markers).isEmpty())
    }

    @Test
    fun `a mixed roster is partitioned to only the in-range markers`() {
        val eligible =
            eligibleUids(
                markerNorth("a_near", 1_000.0),
                markerNorth("b_far", 250_000.0),
                markerNorth("c_near", 14_000.0),
                markerNorth("d_far", 15_500.0),
            )
        assertEquals(listOf("a_near", "c_near"), eligible)
    }

    @Test
    fun `the discovery-session overload applies the same own-position bound`() {
        val eligible =
            WavePresence.waveEligibleSessionsInRange(
                ownLat,
                ownLng,
                listOf(
                    sessionNorth("near", 8_000.0),
                    sessionNorth("far", 320_000.0),
                ),
            ).map { it.uid }
        assertEquals(listOf("near"), eligible)
        assertTrue(
            WavePresence.waveEligibleSessionsInRange(null, null, listOf(sessionNorth("x", 1.0)))
                .isEmpty(),
        )
    }
}
