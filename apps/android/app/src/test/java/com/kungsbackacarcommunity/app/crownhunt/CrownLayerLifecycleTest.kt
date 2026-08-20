package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The retain-vs-clear-vs-poll rule for the auto-spawn crown layer.
 *
 * The load-bearing case is [covered map with the feature on RETAINS the layer]:
 * that is the "reload flash on nav return" fix. Leaving the map for another tab
 * is NOT the feature going away, so the crowns must be kept (the map surface is
 * never disposed, so they stay drawn underneath) rather than cleared and
 * cold-re-fetched on return.
 */
class CrownLayerLifecycleTest {

    @Test
    fun `on the map with the feature on polls`() {
        assertEquals(
            CrownLayerAction.POLL,
            CrownLayerLifecycle.actionFor(onMapTab = true, enabled = true),
        )
    }

    @Test
    fun `covered map with the feature on retains the layer`() {
        // The fix: another tab covers the map but the feature is still enabled, so
        // the crowns are RETAINED (poll stops, nothing is cleared) — no reload
        // flash when the member returns to the map.
        assertEquals(
            CrownLayerAction.RETAIN,
            CrownLayerLifecycle.actionFor(onMapTab = false, enabled = true),
        )
    }

    @Test
    fun `feature off on the map clears the layer`() {
        assertEquals(
            CrownLayerAction.CLEAR,
            CrownLayerLifecycle.actionFor(onMapTab = true, enabled = false),
        )
    }

    @Test
    fun `feature off while covered clears the layer`() {
        // A disabled feature clears regardless of which tab is showing: the flag
        // going off (or an opt-out) must never leave stale crowns painted.
        assertEquals(
            CrownLayerAction.CLEAR,
            CrownLayerLifecycle.actionFor(onMapTab = false, enabled = false),
        )
    }
}
