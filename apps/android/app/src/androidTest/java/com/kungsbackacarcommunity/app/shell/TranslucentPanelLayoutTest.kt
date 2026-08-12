package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The translucent shell panels (History / Social / Garage) — their GEOMETRY:
 * every panel is bottom-anchored and shorter than the safe area (a real strip of
 * live map above it that clears system UI), all share the one labelled drag
 * handle, and the map behind them stays live and un-rebuilt. Dismissal gestures
 * are covered in [TranslucentPanelDismissalTest]. Part of the map-first shell
 * suite split out of `MapFirstShellTest` (see [MapFirstShellTestSupport]).
 */
@RunWith(AndroidJUnit4::class)
class TranslucentPanelLayoutTest : MapFirstShellTestSupport() {

    /**
     * The geometric contract every panel shares, asserted on MEASURED bounds
     * rather than mere existence: the card is bottom-anchored and strictly
     * shorter than the safe area, so a real strip of live map is left uncovered
     * above it. A full-height page (which is what all three of these tabs used to
     * be) reports top = 0 and fails here — that is where this gets its teeth.
     */
    private fun assertPanelLeavesUncoveredMapStrip(tag: String) {
        val cardTop = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top
        assertTrue(
            "panel '$tag' card top was $cardTop — expected a strip of live map above it",
            cardTop > 16.dp,
        )
        // ...and the strip must CLEAR system UI, not merely be non-zero: the
        // fraction is taken against the safe area, so on a short window
        // (landscape / split-screen) the card's top can never slide under the
        // status bar and hide the page's own title.
        val statusBarTop = with(composeTestRule.density) { statusBarHeightPx().toDp() }
        assertTrue(
            "panel '$tag' card top was $cardTop — expected it to clear the " +
                "$statusBarTop status bar",
            cardTop >= statusBarTop,
        )
        // Deliberately NOT asserted here: that the map home is findable behind the
        // card. The shell wraps that subtree in `clearAndSetSemantics {}` while
        // anything covers the map, so TalkBack cannot reach the map's controls
        // through the page on top of them — which also takes MAP_HOME_TEST_TAG out
        // of the semantics tree. "The map behind a panel is still live" is asserted
        // against the surface itself in [translucentPanelTab_keepsTheMapLive], and
        // "the strip is really uncovered and tappable" in
        // [TranslucentPanelDismissalTest.tappingTheUncoveredMapStrip_dismissesThePanel].
    }

    @Test
    fun historyPanel_leavesUncoveredMapStripAboveCard() {
        setShell()
        openTab(R.string.shell_tabHistory)
        assertPanelLeavesUncoveredMapStrip(HISTORY_PANEL_TEST_TAG)
    }

    @Test
    fun socialPanel_leavesUncoveredMapStripAboveCard() {
        setShell()
        openTab(R.string.shell_tabSocial)
        assertPanelLeavesUncoveredMapStrip(SOCIAL_PANEL_TEST_TAG)
    }

    @Test
    fun garagePanel_leavesUncoveredMapStripAboveCard() {
        setShell()
        openTab(R.string.shell_tabGarage)
        assertPanelLeavesUncoveredMapStrip(GARAGE_PANEL_TEST_TAG)
    }

    /**
     * All three tabs use the ONE shared panel component, so all three must expose
     * the same drag handle with the same label. Three bespoke implementations is
     * exactly how that stops being true.
     */
    @Test
    fun everyPanelTab_showsALabelledDragHandle() {
        setShell()
        for (tab in listOf(R.string.shell_tabHistory, R.string.shell_tabSocial, R.string.shell_tabGarage)) {
            openTab(tab)
            // Labelled, not `contentDescription = null`: the handle is the only
            // visible sign the page can be pulled away, so a screen reader has to
            // be able to say so.
            composeTestRule
                .onNodeWithContentDescription(str(R.string.shell_panelDragHandle))
                .assertExists()
        }
    }

    /**
     * The map-behind contract, asserted through the real shell wiring rather than
     * only against the pure `ShellNavigation.mapCover` rule.
     *
     * A translucent panel leaves the map genuinely visible — in the uncovered
     * strip above the card, and faintly through the card itself — so standing the
     * surface down would show the user a map with no puck on it. All three tabs
     * used to stand it down; this is the behaviour change.
     */
    @Test
    fun translucentPanelTab_keepsTheMapLive() {
        val surface = StubMapSurface()
        setShell(mapSurface = surface)
        composeTestRule.runOnIdle { assertTrue(surface.isActive) }

        for (tab in listOf(R.string.shell_tabHistory, R.string.shell_tabSocial, R.string.shell_tabGarage)) {
            openTab(tab)
            composeTestRule.runOnIdle {
                assertTrue(
                    "a translucent panel shows the live map: it must not be stood down",
                    surface.isActive,
                )
            }
            // ...and the map is still the SAME one, never rebuilt.
            composeTestRule.runOnIdle { assertEquals(1, surface.contentCompositions) }
        }
    }
}
