package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrownHuntTest {

    @Test
    fun `all 11 claim results parse from their wire codes`() {
        val expected =
            mapOf(
                "awarded" to CrownHuntClaimResult.AWARDED,
                "already_claimed" to CrownHuntClaimResult.ALREADY_CLAIMED,
                "outside_geofence" to CrownHuntClaimResult.OUTSIDE_GEOFENCE,
                "moving_too_fast" to CrownHuntClaimResult.MOVING_TOO_FAST,
                "position_too_old" to CrownHuntClaimResult.POSITION_TOO_OLD,
                "point_inactive" to CrownHuntClaimResult.POINT_INACTIVE,
                "cooldown_active" to CrownHuntClaimResult.COOLDOWN_ACTIVE,
                "daily_limit_reached" to CrownHuntClaimResult.DAILY_LIMIT_REACHED,
                "risk_review" to CrownHuntClaimResult.RISK_REVIEW,
                "feature_disabled" to CrownHuntClaimResult.FEATURE_DISABLED,
                "not_eligible" to CrownHuntClaimResult.NOT_ELIGIBLE,
            )
        expected.forEach { (wire, result) ->
            assertEquals(result, CrownHuntClaimResult.fromWire(wire))
        }
        assertEquals(11, CrownHuntClaimResult.values().size)
        assertNull(CrownHuntClaimResult.fromWire("bogus"))
    }

    @Test
    fun `point status parses active and rejects unknown`() {
        assertEquals(CrownHuntPointStatus.ACTIVE, CrownHuntPointStatus.fromWire("active"))
        assertEquals(CrownHuntPointStatus.PAUSED, CrownHuntPointStatus.fromWire("paused"))
        assertNull(CrownHuntPointStatus.fromWire("live"))
    }
}
