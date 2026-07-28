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

    // --- route back-stack (hub → child → Back returns to the hub) --------
    // The reported bug: from a sub-menu (Settings → Blocked users) system-Back
    // jumped all the way to the map instead of popping ONE level to the parent
    // hub. These pin the pure push/pop reducer the shell drives from.

    @Test
    fun `opening a child from a hub stacks the hub as its parent`() {
        // Settings is open (no parent); opening Blocked users pushes Settings as
        // its parent, so a later Back knows where to return.
        assertEquals(
            listOf(ShellRoute.Settings),
            ShellNavigation.pushRoute(parents = emptyList(), current = ShellRoute.Settings),
        )
    }

    @Test
    fun `opening a top-level route adds no parent`() {
        // A root entry (map-home menu / push tap) opens with nothing already
        // open, so it stacks no parent and Back from it returns to the map.
        assertEquals(
            emptyList<ShellRoute>(),
            ShellNavigation.pushRoute(parents = emptyList(), current = null),
        )
    }

    @Test
    fun `back from a sub-menu returns to its parent hub, not the map`() {
        // Settings → Blocked users: the parent stack is [Settings]. Back must pop
        // to Settings (current), NOT to null/the map — this is the whole fix.
        val popped = ShellNavigation.popRoute(parents = listOf(ShellRoute.Settings))
        assertEquals(ShellRoute.Settings, popped.current)
        assertEquals(emptyList<ShellRoute>(), popped.parents)
    }

    @Test
    fun `back pops exactly one level of a deeper stack`() {
        // Friends → member profile → chat: parents are [Friends, MemberProfile].
        // Back lands on the member profile, keeping Friends stacked below it.
        val popped =
            ShellNavigation.popRoute(
                parents = listOf(ShellRoute.Friends, ShellRoute.MemberProfile),
            )
        assertEquals(ShellRoute.MemberProfile, popped.current)
        assertEquals(listOf(ShellRoute.Friends), popped.parents)
    }

    @Test
    fun `back from a top-level route leaves nothing open`() {
        // A root route (empty parent stack) pops to null, so the shell falls
        // through to its tab/exit Back rules (return to Map, or exit).
        val popped = ShellNavigation.popRoute(parents = emptyList())
        assertEquals(null, popped.current)
        assertEquals(emptyList<ShellRoute>(), popped.parents)
    }

    @Test
    fun `settings-to-blocked round-trips push then pop back to settings`() {
        // End-to-end of the reported flow using only the pure reducer, so the two
        // halves (open, then Back) are asserted to compose correctly.
        // Open Settings as a root entry, then open Blocked users from it.
        val afterSettings = ShellNavigation.pushRoute(parents = emptyList(), current = null)
        val afterBlocked =
            ShellNavigation.pushRoute(parents = afterSettings, current = ShellRoute.Settings)
        assertEquals(listOf(ShellRoute.Settings), afterBlocked)
        // Back from Blocked users pops to Settings, not the map.
        val popped = ShellNavigation.popRoute(afterBlocked)
        assertEquals(ShellRoute.Settings, popped.current)
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
        // still resolve it. (That the shell UI no longer OFFERS More is a
        // composable-level property, covered by the instrumented popup test and
        // RouteHost's migration branch — not assertable from a JVM unit test.)
        assertEquals(ShellRoute.More, ShellRoute.valueOf("More"))
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

    // --- Live-share sheet rows -------------------------------------------
    // hasStop is what splits the two surfaces: true = the bottom bar's STOP
    // sheet (stopping and NOTHING else), false = turn-by-turn's reuse (no Stop,
    // but the privacy escape hatch and the audience screen unchanged).

    @Test
    fun `stop sheet while sharing offers stopping and nothing else`() {
        val rows =
            LiveManageSheet.actions(isSharing = true, canShareLive = true, hasStop = true)
        assertEquals(true, rows.showStop)
        // Removed from the stop sheet: pressing a stop sign must not open a menu.
        assertEquals(false, rows.showHideNow)
        assertEquals(false, rows.showAudienceEntry)
        // Not a start surface while already sharing.
        assertEquals(false, rows.showStart)
        assertEquals(false, rows.showUnavailableNotice)
    }

    @Test
    fun `manage sheet without a stop handler still keeps hide-now and audience`() {
        // Turn-by-turn navigation reuses the sheet WITHOUT wiring stop, so no
        // Stop row appears there — but the privacy controls must not vanish:
        // dropping them from the STOP sheet must not drop them from the app.
        val rows =
            LiveManageSheet.actions(isSharing = true, canShareLive = true, hasStop = false)
        assertEquals(false, rows.showStop)
        assertEquals(true, rows.showHideNow)
        assertEquals(true, rows.showAudienceEntry)
    }

    @Test
    fun `manage sheet when idle and permitted offers start plus the audience entry`() {
        val rows =
            LiveManageSheet.actions(isSharing = false, canShareLive = true, hasStop = false)
        assertEquals(true, rows.showStart)
        assertEquals(false, rows.showHideNow)
        assertEquals(false, rows.showStop)
        assertEquals(false, rows.showUnavailableNotice)
        // Who-can-see-me is reachable in every state of the controls sheet.
        assertEquals(true, rows.showAudienceEntry)
    }

    @Test
    fun `manage sheet when idle and flag off shows the unavailable notice and audience only`() {
        // canShareLive false = LIVE_LOCATION flag OFF (flag-gated, NOT
        // member-gated). The sheet must explain it's unavailable, never claim a
        // membership is required.
        val rows =
            LiveManageSheet.actions(isSharing = false, canShareLive = false, hasStop = false)
        assertEquals(false, rows.showStart)
        assertEquals(true, rows.showUnavailableNotice)
        assertEquals(false, rows.showStop)
        assertEquals(false, rows.showHideNow)
        assertEquals(true, rows.showAudienceEntry)
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

    @Test
    fun `stub surface starts in day mode and toggles day-night`() {
        val surface = StubMapSurface(autoLoad = false)
        assertEquals(MapMode.Day, surface.mapMode.value)
        surface.setMapMode(MapMode.Night)
        assertEquals(MapMode.Night, surface.mapMode.value)
        surface.setMapMode(MapMode.Day)
        assertEquals(MapMode.Day, surface.mapMode.value)
    }

    @Test
    fun `stub surface starts in 3D and toggles 3D-2D`() {
        val surface = StubMapSurface(autoLoad = false)
        assertEquals(true, surface.is3d.value)
        surface.set3dEnabled(false)
        assertEquals(false, surface.is3d.value)
        surface.set3dEnabled(true)
        assertEquals(true, surface.is3d.value)
    }

    // ── Map cover: is the map visible, and must it stay live? ───────────────
    //
    // The ONE decision the shell derives everything else from — standing the
    // surface down (which kills the pulsing puck and the GPS fixes), painting an
    // opaque page background, clearing the map's semantics, gating the chat hub.
    // Tested here rather than through the composable so the rules cannot be
    // restated (and drift) at any of those call sites.

    @Test
    fun `map tab with nothing over it is uncovered`() {
        assertEquals(
            MapCover.None,
            ShellNavigation.mapCover(
                tab = ShellTab.Map,
                route = null,
                navigating = false,
                navSearchOpen = false,
            ),
        )
    }

    @Test
    fun `translucent panel tabs do NOT stand the map down`() {
        // The regression this whole change turns on. History, Social and Garage
        // are now translucent panels with a strip of live map above them, so the
        // map behind them is genuinely on screen. Returning Opaque here — which
        // is what these three used to do — makes the shell call
        // setActive(false) and shows the user a puck-less map through the card.
        for (tab in listOf(ShellTab.History, ShellTab.Social, ShellTab.Garage)) {
            assertEquals(
                "$tab is a translucent panel: the map behind it must stay live",
                MapCover.Transparent,
                ShellNavigation.mapCover(
                    tab = tab,
                    route = null,
                    navigating = false,
                    navSearchOpen = false,
                ),
            )
        }
    }

    @Test
    fun `every translucent panel tab is declared as one`() {
        // Pins the set and its consumer together: adding a tab to
        // TRANSLUCENT_PANEL_TABS without giving it a panel (or the reverse) is
        // the drift this asserts against.
        assertEquals(
            setOf(ShellTab.History, ShellTab.Social, ShellTab.Garage),
            TRANSLUCENT_PANEL_TABS,
        )
        for (tab in TRANSLUCENT_PANEL_TABS) {
            assertEquals(
                MapCover.Transparent,
                ShellNavigation.mapCover(
                    tab = tab,
                    route = null,
                    navigating = false,
                    navSearchOpen = false,
                ),
            )
        }
    }

    @Test
    fun `a full-screen route over a panel tab hides the map outright`() {
        // A route opened FROM a panel (Social to Events, say) is a full-screen
        // opaque page: the panel is gone, nothing shows the map, so it is stood
        // down. Route beats tab.
        assertEquals(
            MapCover.Opaque,
            ShellNavigation.mapCover(
                tab = ShellTab.Social,
                route = ShellRoute.Events,
                navigating = false,
                navSearchOpen = false,
            ),
        )
    }

    @Test
    fun `turn-by-turn hides the map even over a panel tab`() {
        // Navigation brings its own full-screen map, so the shell's must stand
        // down no matter what is behind it.
        assertEquals(
            MapCover.Opaque,
            ShellNavigation.mapCover(
                tab = ShellTab.Garage,
                route = null,
                navigating = true,
                navSearchOpen = false,
            ),
        )
    }

    @Test
    fun `the address search keeps the map live`() {
        // Unchanged by this feature, asserted so the panel rules cannot quietly
        // take the search's Transparent case with them.
        assertEquals(
            MapCover.Transparent,
            ShellNavigation.mapCover(
                tab = ShellTab.Map,
                route = null,
                navigating = false,
                navSearchOpen = true,
            ),
        )
    }

    // ---- chat hub gate ---------------------------------------------------

    @Test
    fun `chat hub shows over the map home`() {
        assertEquals(
            true,
            ShellNavigation.chatHubAllowed(cover = MapCover.None, navigating = false),
        )
    }

    @Test
    fun `chat hub shows over turn-by-turn`() {
        // Navigation carries the map home's chat control now, so the control has
        // to be able to open the hub. This is the case that used to be excluded.
        assertEquals(
            true,
            ShellNavigation.chatHubAllowed(cover = MapCover.Opaque, navigating = true),
        )
    }

    @Test
    fun `chat hub stays hidden over a full-screen route or a non-map tab`() {
        // Both are MapCover.Opaque WITHOUT navigating: there is no map behind the
        // popup, which is the whole reason the gate exists.
        assertEquals(
            false,
            ShellNavigation.chatHubAllowed(cover = MapCover.Opaque, navigating = false),
        )
    }

    @Test
    fun `chat hub stays hidden over the address search and the translucent panels`() {
        assertEquals(
            false,
            ShellNavigation.chatHubAllowed(cover = MapCover.Transparent, navigating = false),
        )
        // Belt and braces: a stale `navigating` flag must not admit the hub over a
        // cover that is not turn-by-turn.
        assertEquals(
            false,
            ShellNavigation.chatHubAllowed(cover = MapCover.Transparent, navigating = true),
        )
    }
}
