package com.kungsbackacarcommunity.app.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The map-home chrome: the collapsed/expanded search bar, the floating controls,
 * the layers popup (incl. Trafikverket attribution), their stack ORDER, and the
 * two-mode compass. The order/compass/attribution cases render [MapHome] on its
 * own stub surface via [setMapHome]; the search-bar and layers-open cases go
 * through the full shell. Part of the map-first shell suite split out of
 * `MapFirstShellTest` (see [MapFirstShellTestSupport]).
 */
@RunWith(AndroidJUnit4::class)
class MapHomeControlsTest : MapFirstShellTestSupport() {

    /**
     * Renders [MapHome] on its own (stub surface) so the Trafikverket-attribution
     * wiring can be driven directly; the shell-level [setShell] build has no
     * Firebase and therefore never loads any incidents at all.
     */
    private fun setMapHome(
        trafikverketDataShown: Boolean,
        surface: MapSurface = StubMapSurface(),
        incidentReportingEnabled: Boolean = false,
    ) {
        composeTestRule.setContent {
            KccTheme {
                // The incidents-layer toggle is HOISTED out of MapHome (the real
                // host owns it in AuthenticatedApp, so the Map-tab fetch loop can
                // key off it). Its defaults are `true` + a NO-OP callback, so a
                // stub host that leaves them out gets a switch that cannot
                // actually turn off. Hold the state here, exactly as the real host
                // does, or the "turning the layer off hides the credit" assertion
                // below has nothing to assert against.
                var incidentsLayerEnabled by remember { mutableStateOf(true) }
                MapHome(
                    incidentsLayerEnabled = incidentsLayerEnabled,
                    onIncidentsLayerEnabledChange = { incidentsLayerEnabled = it },
                    mapSurface = surface,
                    incidentReportingEnabled = incidentReportingEnabled,
                    isLiveSharing = false,
                    participantCount = 0,
                    userLabel = "Test",
                    onSearch = {},
                    onOpenSavedPlaces = {},
                    moreMenuEntries = emptyList(),
                    trafikverketDataShown = trafikverketDataShown,
                )
            }
        }
    }

    /** Vertical position of a control, for the stack-order assertions below. */
    private fun topOf(tag: String): Float =
        composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value

    private fun topOfDescribed(description: String): Float =
        composeTestRule
            .onNodeWithContentDescription(description)
            .getUnclippedBoundsInRoot()
            .top
            .value

    @Test
    fun mapHome_showsSearchBarAndFloatingControls() {
        setShell()
        // Map-first home renders (MapSurface stub behind the shell).
        composeTestRule.onNodeWithTag(MAP_HOME_TEST_TAG).assertExists()
        // The search bar starts COLLAPSED to a round icon button (upper-left), so
        // the "Where to?" hint is hidden until the button is tapped.
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).assertExists()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).assertDoesNotExist()
        // Floating controls: layers + compass + recenter. (There is no longer a
        // right-side live-share control — its capabilities moved to the centre
        // live control's manage sheet. Order is pinned separately by
        // rightSideControls_areOrderedReportFirst.)
        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).assertExists()
        // Exercise the compass's tap action and prove it doesn't crash the
        // stubbed shell — matching how the other controls are driven. What the
        // tap DOES is asserted by compassControl_togglesOrientation_andRecentresEachTap.
        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_layersButton)).assertExists()
        composeTestRule.onNodeWithTag(MAP_HOME_SAVED_PLACES_TAG).assertExists()
    }

    @Test
    fun searchButton_expandsToFullBar() {
        setShell()
        // Collapsed by default: the round search button is shown, the full bar is
        // not.
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).assertExists()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).assertDoesNotExist()
        // Tapping the round button expands the full-width "Where to?" bar.
        composeTestRule.onNodeWithTag(MAP_HOME_SEARCH_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.shell_searchHint)).assertIsDisplayed()
    }

    @Test
    fun layersControl_opensAndDismissesLayersPopup() {
        setShell()
        // Tapping the layers control opens the transparent map-layers popup.
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_POPUP_TAG).assertIsDisplayed()
        // It exposes the incidents ("Traffic alerts") / traffic / night-mode / 3D
        // toggles.
        composeTestRule.onNodeWithText(str(R.string.shell_layersIncidents)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_layersTraffic)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_layersNightMode)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_layers3d)).assertIsDisplayed()
        // ...and the Kronjakt participation toggle. assertExists rather than
        // scroll-into-view so the header's close button (asserted next) stays put:
        // the popup is a plain verticalScroll Column, so every row is composed.
        composeTestRule.onNodeWithText(str(R.string.shell_layersCrownHunt)).assertExists()
        // Closing dismisses the popup.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_layersClose)).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_POPUP_TAG).assertDoesNotExist()
    }

    /**
     * Querying a [CircleControl] by test tag and by contentDescription must land
     * on the SAME node, so the order assertions below can mix the two freely.
     *
     * They can because `CircleControl` is a clickable `Surface`, which sets
     * `mergeDescendants = true`: the `Icon`'s contentDescription merges UP into
     * the clickable Surface node, and `onNodeWithContentDescription` reads the
     * merged tree by default. So both queries resolve to the Surface — the tag
     * does not select a container while the description selects an inner Icon.
     *
     * This is asserted rather than assumed because it is the one property that
     * would silently corrupt every position comparison in this file if it ever
     * changed (say, someone moves the description onto the Icon with
     * `clearAndSetSemantics`, or drops the merging). The compass is the probe: it
     * is the only control carrying BOTH a tag and a description.
     */
    @Test
    fun circleControl_tagAndContentDescriptionResolveToTheSameNode() {
        setMapHome(trafikverketDataShown = false)
        val byTag = composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).getUnclippedBoundsInRoot()
        val byDescription =
            composeTestRule
                // The compass opens in its default course-up mode, whose
                // description is shell_compassCourseUp (the two-mode toggle).
                .onNodeWithContentDescription(str(R.string.shell_compassCourseUp))
                .getUnclippedBoundsInRoot()
        assertEquals(byTag.top.value.toDouble(), byDescription.top.value.toDouble(), 0.01)
        assertEquals(byTag.left.value.toDouble(), byDescription.left.value.toDouble(), 0.01)
        assertEquals(byTag.bottom.value.toDouble(), byDescription.bottom.value.toDouble(), 0.01)
    }

    /**
     * The right-side stack's ORDER, top-to-bottom:
     * report → layers → compass → saved-places → chat.
     *
     * Pinned by measured position rather than by declaration order, because the
     * order is the whole user-visible point of the change and a reordering of
     * the composables is exactly what would break it. The report control LEADS
     * the stack. (The dedicated recenter/my-location control was removed: the
     * compass re-centres on the user and there is a ~10s idle auto-return.)
     */
    @Test
    fun rightSideControls_areOrderedReportFirst() {
        setMapHome(trafikverketDataShown = false, incidentReportingEnabled = true)
        val report = topOf(MAP_HOME_REPORT_TAG)
        val layers = topOf(MAP_HOME_LAYERS_TAG)
        val compass = topOf(MAP_HOME_COMPASS_TAG)
        val savedPlaces = topOf(MAP_HOME_SAVED_PLACES_TAG)
        val chat = topOfDescribed(str(R.string.shell_chat))

        assertTrue("report must be above layers", report < layers)
        assertTrue("layers must be above the compass", layers < compass)
        assertTrue("the compass must be above saved-places", compass < savedPlaces)
        assertTrue("saved-places must be above chat", savedPlaces < chat)
        // Nothing sits between the compass and the saved-places control.
        listOf(report, layers, chat).forEach {
            assertFalse("no control may sit between compass and saved-places", it in compass..savedPlaces)
        }
    }

    /**
     * With incident reporting unavailable the stack simply STARTS at the layers
     * control: no gap, no placeholder where the report control would be.
     * Asserted as "layers is now the topmost control" AND that it sits exactly
     * one slot above the compass, which a stray spacer or an empty reserved slot
     * would break.
     */
    @Test
    fun rightSideControls_leadWithLayers_whenReportingUnavailable() {
        setMapHome(trafikverketDataShown = false, incidentReportingEnabled = false)
        composeTestRule.onNodeWithTag(MAP_HOME_REPORT_TAG).assertDoesNotExist()
        val layers = topOf(MAP_HOME_LAYERS_TAG)
        val compass = topOf(MAP_HOME_COMPASS_TAG)
        assertTrue("layers must lead the stack", layers < compass)
        // The stack tightened up rather than leaving a hole: layers sits exactly
        // ONE slot above the compass — one control (KccSpacing.s12) plus the
        // Column's spacing (KccSpacing.s3). Asserted against those tokens
        // directly, so the failure message points at the real invariant: a stray
        // spacer or a reserved empty slot widens this gap and nothing else does.
        val oneSlot = KccSpacing.s12.value + KccSpacing.s3.value
        assertEquals(
            "no gap may be left where the report control would have been",
            oneSlot.toDouble(),
            (compass - layers).toDouble(),
            1.0,
        )
    }

    /**
     * The compass is now a TWO-MODE orientation toggle (north-up ⇄ course-up),
     * and each tap must still RE-CENTRE on the user — the old regression ("the
     * north arrow points north but doesn't bring me back to me") must not creep
     * back in either mode.
     *
     * Default is now course-up, so the map home pushes course-up onto the fresh
     * (north-up) surface on OPEN — one re-centre, no north reset — before any tap.
     * Tap 1 → north-up: re-centres AGAIN and NOW resets north. Tap 2 → back to
     * course-up: re-centres once more, no further north reset (course-up faces the
     * heading). [compassMode] tracks the toggle itself. A compass wired only to a
     * bearing change (no re-centre) fails on recenterCount; a toggle that forgot to
     * reset north on the way to north-up fails on resetNorthCount.
     */
    @Test
    fun compassControl_togglesOrientation_andRecentresEachTap() {
        val surface = StubMapSurface()
        setMapHome(trafikverketDataShown = false, surface = surface)

        // On open the course-up default is applied to the north-up surface: one
        // re-centre, no north reset, and the surface is already course-up.
        composeTestRule.runOnIdle {
            assertEquals("opens in course-up", MapCompassMode.CourseUp, surface.compassMode)
            assertEquals("applying the course-up default re-centres once", 1, surface.recenterCount)
            assertEquals("course-up does not reset north", 0, surface.resetNorthCount)
        }

        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).performClick()
        composeTestRule.runOnIdle {
            assertEquals("first tap switches to north-up", MapCompassMode.NorthUp, surface.compassMode)
            assertEquals("north-up must re-centre on the user", 2, surface.recenterCount)
            assertEquals("switching to north-up must reset north", 1, surface.resetNorthCount)
        }

        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).performClick()
        composeTestRule.runOnIdle {
            assertEquals("second tap returns to course-up", MapCompassMode.CourseUp, surface.compassMode)
            assertEquals("course-up must still re-centre on the user", 3, surface.recenterCount)
            assertEquals("returning to course-up does not reset north again", 1, surface.resetNorthCount)
        }
    }

    /**
     * The compass ICON changes with the mode, so its (localized) content
     * description flips too: course-up by default, north-up after one tap, and
     * back. Only the icon/description changes — the control keeps its default
     * container/content colours in both modes (a colour change would be a visual
     * regression, and there is nothing here that changes them).
     */
    @Test
    fun compassControl_iconReflectsTheMode() {
        setMapHome(trafikverketDataShown = false)
        // Default: course-up.
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_compassCourseUp)).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_compassNorthUp)).assertDoesNotExist()
        // Tap → north-up.
        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).performClick()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_compassNorthUp)).assertExists()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_compassCourseUp)).assertDoesNotExist()
        // Tap → back to course-up.
        composeTestRule.onNodeWithTag(MAP_HOME_COMPASS_TAG).performClick()
        composeTestRule.onNodeWithContentDescription(str(R.string.shell_compassCourseUp)).assertExists()
    }

    @Test
    fun layersPopup_showsTrafikverketAttribution_whenTheirDataIsLoaded() {
        setMapHome(trafikverketDataShown = true)
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_POPUP_TAG).assertIsDisplayed()
        // The incidents layer defaults ON and Trafikverket-sourced incidents are
        // loaded, so we owe (and show) the "Källa: Trafikverket" credit.
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_INCIDENTS_TAG).assertIsDisplayed()
        // The popup scrolls now (the layer list outgrew a short screen), so scroll
        // the bottom-most attribution into view before asserting it shows.
        composeTestRule
            .onNodeWithText(str(R.string.incidents_sourceTrafikverket))
            .performScrollTo()
            .assertIsDisplayed()
        // Turning the incidents layer off removes the attribution (no Trafikverket
        // data is on screen to credit) — the conditional wiring this test guards.
        // Scroll the toggle back into view first (we scrolled to the bottom above).
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_INCIDENTS_TAG).performScrollTo().performClick()
        composeTestRule
            .onNodeWithText(str(R.string.incidents_sourceTrafikverket))
            .assertDoesNotExist()
    }

    @Test
    fun layersPopup_hidesTrafikverketAttribution_whenNoneOfTheirDataIsLoaded() {
        // The abroad case: the layer is on, but the Sweden-only importer
        // contributes nothing outside Sweden, so there is no Trafikverket data on
        // screen and crediting them would be a false claim. (Same for a Swedish
        // area with no active imported incidents.)
        setMapHome(trafikverketDataShown = false)
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_TAG).performClick()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_POPUP_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MAP_HOME_LAYERS_INCIDENTS_TAG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.incidents_sourceTrafikverket))
            .assertDoesNotExist()
    }
}
