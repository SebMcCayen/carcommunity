package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.navigation.turnbyturn.NavHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where an accepted invite goes, and what the dissolve is made of. */
class ConvoyAcceptHandoffTest {

    @Test
    fun `a successful accept with a map host hands off`() {
        assertEquals(
            ConvoyAcceptNav.FadeToMap,
            ConvoyAcceptHandoff.navFor(succeeded = true, hasMapHost = true),
        )
    }

    @Test
    fun `a failed accept stays put so its error is on the screen that raised it`() {
        assertEquals(
            ConvoyAcceptNav.Stay,
            ConvoyAcceptHandoff.navFor(succeeded = false, hasMapHost = true),
        )
    }

    @Test
    fun `no map host means nowhere to go, success or not`() {
        // A config-less / test surface has no map to dissolve into; fading the
        // list out onto nothing would be strictly worse than staying.
        assertEquals(
            ConvoyAcceptNav.Stay,
            ConvoyAcceptHandoff.navFor(succeeded = true, hasMapHost = false),
        )
        assertEquals(
            ConvoyAcceptNav.Stay,
            ConvoyAcceptHandoff.navFor(succeeded = false, hasMapHost = false),
        )
    }

    @Test
    fun `the surface is opaque until the hand-off starts, then fades away`() {
        assertEquals(ConvoyAcceptHandoff.START_ALPHA, ConvoyAcceptHandoff.contentAlpha(false), 0f)
        assertEquals(ConvoyAcceptHandoff.END_ALPHA, ConvoyAcceptHandoff.contentAlpha(true), 0f)
        assertEquals(1f, ConvoyAcceptHandoff.START_ALPHA, 0f)
        assertEquals(0f, ConvoyAcceptHandoff.END_ALPHA, 0f)
    }

    /**
     * One app, one transition tempo. The shell's tab crossfade and the
     * turn-by-turn dissolve already run at this length; a third duration
     * invented here would make the same gesture feel different depending on
     * which screen it started from.
     */
    @Test
    fun `the dissolve runs at the shell's existing transition tempo`() {
        assertEquals(NavHandoff.FADE_MILLIS, ConvoyAcceptHandoff.FADE_MILLIS)
    }

    @Test
    fun `the dissolve is brief - a transition, not a celebration screen`() {
        assertTrue(ConvoyAcceptHandoff.FADE_MILLIS in 1..400)
    }
}
