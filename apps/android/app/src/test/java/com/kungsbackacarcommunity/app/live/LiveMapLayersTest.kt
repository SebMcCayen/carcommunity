package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported bug was "in Navigation I had trouble seeing other people sharing
 * their live location". The structural fix gives turn-by-turn its own overlay
 * slot; this pins the part that would otherwise drift — that the navigation map
 * decides who to draw from the SAME rosters, by the SAME rule, as the map home.
 */
class LiveMapLayersTest {
    @Test
    fun `nothing to draw when both rosters are empty`() {
        val plan = LiveMapLayers.plan(convoyMemberCount = 0, nearbySharerCount = 0)
        assertFalse(plan.convoy)
        assertFalse(plan.nearby)
        assertFalse(plan.any)
    }

    @Test
    fun `convoy members alone draw only the convoy layer`() {
        val plan = LiveMapLayers.plan(convoyMemberCount = 3, nearbySharerCount = 0)
        assertTrue(plan.convoy)
        assertFalse(plan.nearby)
        assertTrue(plan.any)
    }

    @Test
    fun `nearby sharers alone draw only the nearby layer`() {
        val plan = LiveMapLayers.plan(convoyMemberCount = 0, nearbySharerCount = 2)
        assertFalse(plan.convoy)
        assertTrue(plan.nearby)
        assertTrue(plan.any)
    }

    @Test
    fun `both rosters draw both layers`() {
        val plan = LiveMapLayers.plan(convoyMemberCount = 1, nearbySharerCount = 1)
        assertTrue(plan.convoy)
        assertTrue(plan.nearby)
    }

    /**
     * The entitlement guarantee, expressed the only way a pure function can:
     * whatever the caller was allowed to see is what it was handed, and an empty
     * roster — which is what a viewer who may not see anyone is left with after
     * the backend gating upstream — draws nothing. Navigation cannot widen that,
     * because it asks the same question with the same numbers.
     */
    @Test
    fun `a viewer with no visible members draws nothing on either map`() {
        val mapHome = LiveMapLayers.plan(convoyMemberCount = 0, nearbySharerCount = 0)
        val navigation = LiveMapLayers.plan(convoyMemberCount = 0, nearbySharerCount = 0)
        assertEquals(mapHome, navigation)
        assertFalse(navigation.any)
    }

    /** Same inputs, same plan — the map home and navigation cannot disagree. */
    @Test
    fun `the plan is a pure function of the roster sizes`() {
        listOf(0 to 0, 0 to 5, 4 to 0, 2 to 7).forEach { (convoy, nearby) ->
            assertEquals(
                LiveMapLayers.plan(convoy, nearby),
                LiveMapLayers.plan(convoy, nearby),
            )
        }
    }
}
