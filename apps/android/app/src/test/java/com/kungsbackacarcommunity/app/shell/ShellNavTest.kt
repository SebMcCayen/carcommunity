package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the map-first shell's pure navigation + toggle logic:
 * tab/back resolution and the live-location-share toggle decision.
 */
class ShellNavTest {

    // --- system Back resolution ------------------------------------------

    @Test
    fun `back closes an open route first`() {
        assertEquals(
            ShellBackResult.CloseRoute,
            ShellNavigation.onBack(ShellTab.Social, ShellRoute.Events),
        )
        // Even on the Map tab, an open route closes before anything else.
        assertEquals(
            ShellBackResult.CloseRoute,
            ShellNavigation.onBack(ShellTab.Map, ShellRoute.Profile),
        )
    }

    @Test
    fun `back from a non-Map tab returns to the Map tab`() {
        assertEquals(
            ShellBackResult.GoToMapTab,
            ShellNavigation.onBack(ShellTab.History, null),
        )
        assertEquals(
            ShellBackResult.GoToMapTab,
            ShellNavigation.onBack(ShellTab.Garage, null),
        )
    }

    @Test
    fun `back from the Map tab with nothing open exits`() {
        assertEquals(
            ShellBackResult.Exit,
            ShellNavigation.onBack(ShellTab.Map, null),
        )
    }

    @Test
    fun `the default tab is Map`() {
        // Assert the real default constant (used by production), not enum order.
        assertEquals(ShellTab.Map, ShellTab.DEFAULT)
    }

    // --- live-share toggle decision --------------------------------------

    @Test
    fun `toggle opens the screen when not wired`() {
        assertEquals(
            LiveShareAction.OpenScreen,
            LiveShareToggle.action(isSharing = false, canShare = true, wired = false),
        )
        // Not-wired always opens the screen, even if "sharing" were true.
        assertEquals(
            LiveShareAction.OpenScreen,
            LiveShareToggle.action(isSharing = true, canShare = true, wired = false),
        )
    }

    @Test
    fun `toggle stops when currently sharing`() {
        assertEquals(
            LiveShareAction.Stop,
            LiveShareToggle.action(isSharing = true, canShare = true, wired = true),
        )
    }

    @Test
    fun `toggle starts when wired, not sharing and allowed`() {
        assertEquals(
            LiveShareAction.Start,
            LiveShareToggle.action(isSharing = false, canShare = true, wired = true),
        )
    }

    @Test
    fun `toggle opens the screen when start is not permitted`() {
        assertEquals(
            LiveShareAction.OpenScreen,
            LiveShareToggle.action(isSharing = false, canShare = false, wired = true),
        )
    }
}
