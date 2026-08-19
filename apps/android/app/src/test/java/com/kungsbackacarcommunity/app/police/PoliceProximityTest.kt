package com.kungsbackacarcommunity.app.police

import com.kungsbackacarcommunity.app.navigation.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure police proximity-alert decision — within threshold + not-already-
 * alerted → alert, ONCE per pin. Pinned in the blocking unit-test job; the map
 * host that drives it is the device-only part.
 */
class PoliceProximityTest {
    private val baseLat = 57.4879
    private val baseLng = 12.0756

    /** A point [metres] north of the base (1 deg lat ≈ 111_320 m). */
    private fun north(metres: Double) = LatLng(longitude = baseLng, latitude = baseLat + metres / 111_320.0)

    private fun pin(id: String, at: LatLng, expiresIso: String? = FAR_FUTURE, mine: Boolean = false) =
        PoliceReport(
            id = id,
            latitude = at.latitude,
            longitude = at.longitude,
            source = "manual",
            expiresAtIso = expiresIso,
            mine = mine,
        )

    @Test
    fun `alerts a pin within the threshold that has not alerted yet`() {
        val driver = north(PoliceProximity.ALERT_RADIUS_METERS - 50)
        val pins = listOf(pin("a", LatLng(longitude = baseLng, latitude = baseLat)))
        val result = PoliceProximity.newAlerts(driver, pins, emptySet())
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `does not alert a pin beyond the threshold`() {
        val driver = north(PoliceProximity.ALERT_RADIUS_METERS + 300)
        val pins = listOf(pin("a", LatLng(longitude = baseLng, latitude = baseLat)))
        assertTrue(PoliceProximity.newAlerts(driver, pins, emptySet()).isEmpty())
    }

    @Test
    fun `never re-alerts a pin already in the alerted set - once per pin`() {
        val driver = north(10.0)
        val pins = listOf(pin("a", LatLng(longitude = baseLng, latitude = baseLat)))
        assertTrue(PoliceProximity.newAlerts(driver, pins, setOf("a")).isEmpty())
    }

    @Test
    fun `returns only the new in-range pins from a mixed batch`() {
        val center = LatLng(longitude = baseLng, latitude = baseLat)
        val pins =
            listOf(
                pin("near-new", north(100.0)),
                pin("near-alerted", north(120.0)),
                pin("far", north(5_000.0)),
            )
        val result = PoliceProximity.newAlerts(north(0.0).let { center }, pins, setOf("near-alerted"))
        assertEquals(listOf("near-new"), result.map { it.id })
    }

    @Test
    fun `de-dupes a repeated id within one batch`() {
        val driver = LatLng(longitude = baseLng, latitude = baseLat)
        val at = north(50.0)
        val pins = listOf(pin("dup", at), pin("dup", at))
        assertEquals(1, PoliceProximity.newAlerts(driver, pins, emptySet()).size)
    }

    @Test
    fun `never alerts a pin the driver reported themselves - no self-alert`() {
        val driver = north(10.0)
        // In range and never alerted before, but it's the driver's OWN pin.
        val pins = listOf(pin("mine", LatLng(longitude = baseLng, latitude = baseLat), mine = true))
        assertTrue(PoliceProximity.newAlerts(driver, pins, emptySet()).isEmpty())
    }

    @Test
    fun `alerts a nearby other-reported pin but never the driver's own in the same batch`() {
        val driver = LatLng(longitude = baseLng, latitude = baseLat)
        val pins =
            listOf(
                pin("mine", north(50.0), mine = true),
                pin("theirs", north(80.0)),
            )
        assertEquals(
            listOf("theirs"),
            PoliceProximity.newAlerts(driver, pins, emptySet()).map { it.id },
        )
    }

    @Test
    fun `null driver location yields no alerts`() {
        val pins = listOf(pin("a", LatLng(longitude = baseLng, latitude = baseLat)))
        assertTrue(PoliceProximity.newAlerts(null, pins, emptySet()).isEmpty())
    }

    @Test
    fun `skips a pin with a corrupt coordinate rather than throwing`() {
        val driver = LatLng(longitude = baseLng, latitude = baseLat)
        val pins =
            listOf(
                PoliceReport("bad", Double.NaN, baseLng, "manual", FAR_FUTURE),
                pin("good", north(50.0)),
            )
        assertEquals(listOf("good"), PoliceProximity.newAlerts(driver, pins, emptySet()).map { it.id })
    }

    @Test
    fun `isWithinRange honours a custom radius`() {
        val driver = LatLng(longitude = baseLng, latitude = baseLat)
        val p = pin("a", north(300.0))
        assertFalse(PoliceProximity.isWithinRange(driver, p, radiusMeters = 200.0))
        assertTrue(PoliceProximity.isWithinRange(driver, p, radiusMeters = 400.0))
    }

    @Test
    fun `isLiveAt treats a past expiry as not live and a null expiry as not live`() {
        val nowIso = "2026-08-19T10:00:00.000Z"
        val now = PoliceReportTime.parseIsoMillis(nowIso)!!
        val livePin = pin("live", north(0.0), expiresIso = "2026-08-19T10:30:00.000Z")
        val expiredPin = pin("expired", north(0.0), expiresIso = "2026-08-19T09:30:00.000Z")
        val nullPin = pin("null", north(0.0), expiresIso = null)
        assertTrue(livePin.isLiveAt(now))
        assertFalse(expiredPin.isLiveAt(now))
        assertFalse(nullPin.isLiveAt(now))
    }

    private companion object {
        const val FAR_FUTURE = "2099-01-01T00:00:00.000Z"
    }
}
