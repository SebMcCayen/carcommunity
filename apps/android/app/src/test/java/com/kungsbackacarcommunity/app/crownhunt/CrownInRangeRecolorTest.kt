package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the bug where a crown greyed while out of collect range but
 * never lit to its rarity colour once the member drove into range.
 *
 * The greying/colouring HELPERS were never the fault — this test proves it. It
 * exercises the same per-crown DECISION `AuthenticatedApp` makes for each marker
 * ([CrownRange.isInRange] against the member's location → that crown's
 * [CrownMarkerStyle.discColorArgb]) — the step the app runs inside its
 * spawn-filter/in-range-set build — and shows the disc flips grey → rarity the
 * instant the FED location crosses the ring. The real defect was
 * upstream: the map poll read `CurrentLocation.lastKnown`, whose passive
 * `lastLocation` cache never advanced as the member moved, so the location fed
 * to this pipeline stayed stale and the crown stayed grey. The fix feeds it a
 * fresh fix (`CurrentLocation.currentFix`); this test pins that WHEN the
 * location advances, the colour follows.
 */
class CrownInRangeRecolorTest {
    // A crown in central Kungsbacka, 75 m collect ring.
    private val crownLat = 57.4879
    private val crownLon = 12.0756
    private val radius = 75.0

    /** The disc colour the map would draw for a member standing at (lat, lon). */
    private fun discColorFor(rarity: CrownRarity, userLat: Double, userLon: Double): Int {
        val inRange =
            CrownRange.isInRange(userLat, userLon, crownLat, crownLon, radius)
        return CrownMarkerStyle.discColorArgb(rarity, inRange)
    }

    @Test
    fun crownRecoloursWhenTheMemberCrossesTheRing() {
        for (rarity in CrownRarity.entries) {
            // ~500 m north of the crown: well outside the 75 m ring → slate.
            val far = discColorFor(rarity, crownLat + 0.0045, crownLon)
            assertEquals(
                "out of range → out-of-range slate ($rarity)",
                CrownMarkerStyle.OUT_OF_RANGE_DISC,
                far,
            )

            // ~30 m north of the crown: inside the ring → rarity colour lights up.
            val near = discColorFor(rarity, crownLat + 0.00027, crownLon)
            assertEquals(
                "in range → rarity disc lights up ($rarity)",
                CrownMarkerStyle.discColorArgb(rarity),
                near,
            )
        }
    }

    @Test
    fun aStaleLocationThatNeverAdvancesKeepsTheCrownGrey() {
        // The failure mode: the fed location is a stale far fix that never moves,
        // even though the member has actually driven onto the crown. The pipeline
        // is faithful — grey in, grey out — which is why the fix had to make the
        // location itself advance, not touch the colour rule.
        val staleFar = crownLat + 0.0045
        repeat(5) {
            assertEquals(
                "a location that never advances stays out of range → grey",
                CrownMarkerStyle.OUT_OF_RANGE_DISC,
                discColorFor(CrownRarity.LEGENDARY, staleFar, crownLon),
            )
        }
    }

    @Test
    fun adminPointRecoloursOnTheSameCrossing() {
        val far = CrownRange.isInRange(crownLat + 0.0045, crownLon, crownLat, crownLon, radius)
        val near = CrownRange.isInRange(crownLat + 0.00027, crownLon, crownLat, crownLon, radius)
        assertEquals(
            CrownMarkerStyle.OUT_OF_RANGE_DISC,
            CrownMarkerStyle.adminPointDiscArgb(far),
        )
        assertEquals(
            CrownMarkerStyle.ADMIN_POINT_DISC,
            CrownMarkerStyle.adminPointDiscArgb(near),
        )
    }
}
