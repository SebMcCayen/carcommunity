package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route-preview sheet's DECISIONS, tested where they live.
 *
 * [RouteSheetDrag] and [RouteSheetMetrics] are pure on purpose, so these are
 * assertions about the sheet's behaviour rather than "a drag happened and
 * something changed". Each case below fails against a plausible wrong
 * implementation — a distance-only settle, one that ignores an upward flick, one
 * that lets the sheet swallow a mid-list scroll, one that keeps travelling past
 * the collapsed peek (this sheet is not dismissible), or one that reserves the
 * EXPANDED height of the sheet in the camera fit, which is exactly the bug that
 * left the route framed into the top half of the screen.
 */
class RouteSheetDragTest {
    /** A representative reveal range (px): ~260dp of step list at 3x density. */
    private val rangePx = 780f

    // ---- default state -----------------------------------------------------

    @Test
    fun collapsed_isZeroReveal_soANewRouteShowsTheMapNotTheManeuvers() {
        // The whole point of the sheet: the moment a route appears the directions
        // are OUT of the way. Collapsed must therefore mean "nothing revealed",
        // not "a bit revealed" — a non-zero collapsed reveal would put a slice of
        // the step list permanently over the map.
        assertEquals(0f, RouteSheetDrag.revealForDetent(RouteSheetDetent.Collapsed, rangePx), 0f)
        assertEquals(
            rangePx,
            RouteSheetDrag.revealForDetent(RouteSheetDetent.Expanded, rangePx),
            0f,
        )
    }

    @Test
    fun detentsAreExactlyTwo_andToggleSwapsThem() {
        // A third "half" detent would have to show a route-dependent slice of a
        // variable-length maneuver list, which reads as an accident. Two states,
        // and the handle tap moves between them.
        assertEquals(2, RouteSheetDetent.entries.size)
        assertEquals(RouteSheetDetent.Expanded, RouteSheetDrag.toggle(RouteSheetDetent.Collapsed))
        assertEquals(RouteSheetDetent.Collapsed, RouteSheetDrag.toggle(RouteSheetDetent.Expanded))
    }

    @Test
    fun noRange_alwaysSettlesCollapsed_soNoGestureCanStrandItOverAnEmptyList() {
        // No route yet, a routing error, or a route with no steps: there is
        // nothing to reveal, so neither a long drag nor a hard upward flick may
        // leave the sheet open over a void.
        assertEquals(
            RouteSheetDetent.Collapsed,
            RouteSheetDrag.settleDetent(revealPx = 500f, velocityPxPerSecond = -5_000f, rangePx = 0f),
        )
        assertEquals(0f, RouteSheetDrag.revealForDetent(RouteSheetDetent.Expanded, 0f), 0f)
        // A negative range is a degenerate measurement, not an invitation to
        // travel backwards past the peek.
        assertEquals(0f, RouteSheetDrag.revealForDetent(RouteSheetDetent.Expanded, -10f), 0f)
        assertEquals(0f, RouteSheetDrag.clampReveal(-5f, -10f), 0f)
    }

    // ---- settle ------------------------------------------------------------

    @Test
    fun slowRelease_settlesToTheNEARERDetent() {
        val slow = RouteSheetDrag.FLING_VELOCITY_PX_PER_SECOND / 4f
        assertEquals(
            RouteSheetDetent.Collapsed,
            RouteSheetDrag.settleDetent(rangePx * 0.2f, slow, rangePx),
        )
        assertEquals(
            RouteSheetDetent.Expanded,
            RouteSheetDrag.settleDetent(rangePx * 0.8f, -slow, rangePx),
        )
        // Exactly at the halfway line the sheet opens — a release that got the
        // list half-way up was going for the list.
        assertEquals(
            RouteSheetDetent.Expanded,
            RouteSheetDrag.settleDetent(rangePx * RouteSheetDrag.SETTLE_FRACTION, 0f, rangePx),
        )
    }

    @Test
    fun flick_beatsDistance_inBothDirections() {
        val fling = RouteSheetDrag.FLING_VELOCITY_PX_PER_SECOND
        // Thrown UP from barely off the peek: expand. A distance-only rule
        // collapses here.
        assertEquals(
            RouteSheetDetent.Expanded,
            RouteSheetDrag.settleDetent(rangePx * 0.05f, -fling, rangePx),
        )
        // Thrown DOWN from almost fully open: collapse. The user changed their
        // mind mid-gesture; a distance-only rule expands here.
        assertEquals(
            RouteSheetDetent.Collapsed,
            RouteSheetDrag.settleDetent(rangePx * 0.95f, fling, rangePx),
        )
    }

    @Test
    fun settleFraction_isTheMidpoint_soNeitherDetentIsFavoured() {
        // A lower fraction biases towards expanding, i.e. back towards the
        // take-over-the-screen behaviour this sheet exists to remove.
        assertEquals(0.5f, RouteSheetDrag.SETTLE_FRACTION, 0f)
    }

    // ---- travel limits -----------------------------------------------------

    @Test
    fun reveal_neverEscapesItsTwoDetents() {
        assertEquals(0f, RouteSheetDrag.clampReveal(-400f, rangePx), 0f)
        assertEquals(rangePx, RouteSheetDrag.clampReveal(rangePx + 400f, rangePx), 0f)
        assertEquals(123f, RouteSheetDrag.clampReveal(123f, rangePx), 0f)
    }

    // ---- nested scroll: list vs sheet --------------------------------------

    @Test
    fun midListScroll_isNotSwallowedByTheSheet() {
        // Fully expanded, dragging DOWN: the list gets it first (pre-scroll takes
        // nothing), and mid-list it consumes everything so nothing reaches the
        // post-scroll hook. The sheet must not collapse out from under a user who
        // is reading maneuver 12.
        assertEquals(
            0f,
            RouteSheetDrag.preScrollConsumption(availableY = 60f, revealPx = rangePx, rangePx = rangePx),
            0f,
        )
        assertEquals(0f, RouteSheetDrag.postScrollConsumption(availableY = 0f, revealPx = rangePx), 0f)
    }

    @Test
    fun atTopOverScroll_collapsesTheSheet_andStopsDeadAtThePeek() {
        // List at its top: the whole delta arrives unconsumed at post-scroll and
        // the sheet takes it...
        assertEquals(
            60f,
            RouteSheetDrag.postScrollConsumption(availableY = 60f, revealPx = rangePx),
            0f,
        )
        // ...but only as far as the peek. This sheet is a permanent bottom
        // fixture, not a dismissible panel, so the leftover must be handed back
        // rather than dragging the sheet off-screen.
        assertEquals(20f, RouteSheetDrag.postScrollConsumption(availableY = 60f, revealPx = 20f), 0f)
        assertEquals(0f, RouteSheetDrag.postScrollConsumption(availableY = 60f, revealPx = 0f), 0f)
    }

    @Test
    fun upwardScroll_finishesOpeningTheSheetBeforeTheListMoves() {
        // Half-open, dragging UP: the sheet takes the outstanding travel first
        // (negative = upward), and no more than that, so the same gesture
        // continues into the list from the fully open position.
        val half = rangePx / 2f
        assertEquals(
            -half,
            RouteSheetDrag.preScrollConsumption(availableY = -rangePx, revealPx = half, rangePx = rangePx),
            0.001f,
        )
        // Already fully open: nothing outstanding, so an upward drag goes
        // straight to the list.
        assertEquals(
            0f,
            RouteSheetDrag.preScrollConsumption(availableY = -rangePx, revealPx = rangePx, rangePx = rangePx),
            0f,
        )
    }

    // ---- camera contract ---------------------------------------------------

    @Test
    fun collapsedHeight_isTheCardMinusTheRevealedList() {
        assertEquals(400, RouteSheetDrag.collapsedHeightPx(sheetHeightPx = 1000, stepsHeightPx = 600))
        // Fully collapsed: the whole card IS the peek.
        assertEquals(400, RouteSheetDrag.collapsedHeightPx(sheetHeightPx = 400, stepsHeightPx = 0))
        // A skewed pair of measurements must never produce a negative padding.
        assertEquals(0, RouteSheetDrag.collapsedHeightPx(sheetHeightPx = 100, stepsHeightPx = 600))
    }

    @Test
    fun cameraPad_reservesTheCOLLAPSEDSheet_notTheExpandedOne() {
        val density = 3f
        val collapsed = 400
        val pad = RouteSheetMetrics.cameraBottomPadPx(collapsed, density)
        assertEquals(collapsed + RouteSheetMetrics.CAMERA_CLEARANCE_DP * density, pad, 0.001f)
        // The point of the whole exercise: the route is framed above a PEEK, so
        // the reservation is far smaller than the old always-expanded ~320dp.
        assertTrue(
            "expected the collapsed reservation to be smaller than the old expanded one",
            pad < 320f * density,
        )
    }

    @Test
    fun cameraPad_fallsBackToAPhoneSizedPeek_beforeTheSheetIsMeasured() {
        val density = 3f
        val fallback = RouteSheetMetrics.cameraBottomPadPx(collapsedSheetHeightPx = 0, density = density)
        assertEquals(
            (RouteSheetMetrics.COLLAPSED_FALLBACK_DP + RouteSheetMetrics.CAMERA_CLEARANCE_DP) * density,
            fallback,
            0.001f,
        )
        // Still a peek-sized guess, not an expanded-sheet-sized one.
        assertTrue(
            "the pre-measure fallback must not re-introduce the expanded reservation",
            fallback < 320f * density,
        )
    }

    // ---- reveal sizing -----------------------------------------------------

    @Test
    fun revealHeight_isCappedByBOTHAFixedMaxAndAFractionOfTheWindow() {
        // Tall portrait phone: the fixed cap binds, so the list is a consistent
        // size across normal devices.
        assertEquals(
            RouteSheetMetrics.STEPS_MAX_HEIGHT_DP,
            RouteSheetMetrics.stepsRevealHeightDp(900f),
            0.001f,
        )
        // Short window (landscape / split screen / foldable cover): the fraction
        // binds instead, so the expanded sheet cannot go back to owning most of
        // the screen.
        val short = 360f
        assertEquals(
            short * RouteSheetMetrics.STEPS_MAX_HEIGHT_FRACTION,
            RouteSheetMetrics.stepsRevealHeightDp(short),
            0.001f,
        )
        assertTrue(
            "the expanded list must never claim half a short window",
            RouteSheetMetrics.stepsRevealHeightDp(short) < short / 2f,
        )
        // A degenerate measurement is a zero reveal, not a negative one.
        assertEquals(0f, RouteSheetMetrics.stepsRevealHeightDp(-100f), 0f)
    }
}
