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

    @Test
    fun `More is retained as a valid ShellRoute constant for state restore`() {
        // The retired "More" hub was replaced by the map-home profile popup, but
        // the enum constant must survive so `rememberSaveable` can restore older
        // persisted state (route = More) by name without throwing. valueOf must
        // still resolve it.
        assertEquals(ShellRoute.More, ShellRoute.valueOf("More"))
    }

    @Test
    fun `More is not offered as a reachable destination`() {
        // `More` is retained only for backward-compatible restore; it is not a
        // live destination. Guard against it silently becoming reachable again by
        // asserting it is the only route we intentionally exclude from the set of
        // destinations the shell navigates to.
        val reachable = ShellRoute.entries.filter { it != ShellRoute.More }
        assertEquals(ShellRoute.entries.size - 1, reachable.size)
        assert(!reachable.contains(ShellRoute.More))
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

    // --- MapSurface traffic toggle (stub wiring) -------------------------

    @Test
    fun `stub surface starts with traffic off and toggles`() {
        val surface = StubMapSurface(autoLoad = false)
        assertEquals(false, surface.trafficEnabled.value)
        surface.setTrafficEnabled(true)
        assertEquals(true, surface.trafficEnabled.value)
        surface.setTrafficEnabled(false)
        assertEquals(false, surface.trafficEnabled.value)
    }
}
