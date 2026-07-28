package com.kungsbackacarcommunity.app.navigation

import com.kungsbackacarcommunity.app.shell.PanelDrag

/**
 * A resting position of the route-preview sheet.
 *
 * Two detents only, deliberately. A half state would have to mean "some of the
 * step list", and there is no honest place to cut it: the list is a variable
 * number of maneuvers, so a half-open sheet shows a different amount of
 * information per route and reads as an accident rather than a position. The
 * two states here are the two questions the user actually has — "where does the
 * route go?" (map, collapsed) and "what are the turns?" (list, expanded) — and
 * BOTH show the summary and the Start button, so neither state is a dead end.
 */
enum class RouteSheetDetent {
    /** Peek height: destination, distance/ETA and Start. The map shows the route. */
    Collapsed,

    /** Peek height plus the revealed step-by-step directions list. */
    Expanded,
}

/**
 * Pure (Android-free, Compose-free) geometry and gesture arithmetic for the
 * route-preview sheet, so the sheet's actual DECISIONS — which detent a release
 * settles to, how far a drag may travel, and how a drag is split between the
 * step list and the sheet — are JVM-unit-testable instead of only reachable
 * through an instrumentation drag.
 *
 * **State representation.** One number, `revealPx`: how much of the directions
 * list is currently revealed. `0` is [RouteSheetDetent.Collapsed] and `rangePx`
 * is [RouteSheetDetent.Expanded]; nothing else is a resting value. The sheet is
 * bottom-anchored and GROWS UPWARDS rather than translating, so the Start button
 * stays pinned above the navigation bar in both detents — which is the whole
 * requirement ("Start visible in both states") expressed as geometry rather than
 * as a rule that some later layout change could quietly break.
 *
 * The nested-scroll split reuses [PanelDrag] — the same rule the translucent
 * shell panels (History / Social / Garage) and the chat hub already use — so a
 * drag over a list means the same thing everywhere in the app. Only the
 * post-scroll direction is re-stated here, because [PanelDrag]'s is deliberately
 * unbounded (its panel is dismissible, so "keep going down" has no floor) while
 * this sheet must stop dead at its collapsed detent.
 */
internal object RouteSheetDrag {
    /**
     * Fraction of the travel range a release must be past for it to settle
     * EXPANDED rather than collapsed.
     *
     * Half: the sheet has exactly two detents, so "nearest one wins" is the only
     * rule that makes both equally easy to reach. A lower value would bias
     * towards expanding — which is exactly the take-over-the-screen behaviour
     * this sheet exists to remove.
     */
    const val SETTLE_FRACTION = 0.5f

    /**
     * Speed (px/s) at which a release settles in the direction it was thrown,
     * however far it actually travelled. Shared value with [PanelDrag], so a
     * flick feels the same on this sheet as on the shell panels.
     */
    const val FLING_VELOCITY_PX_PER_SECOND = PanelDrag.FLING_DISMISS_VELOCITY_PX_PER_SECOND

    /** The reveal a settled [detent] corresponds to, for a [rangePx] travel range. */
    fun revealForDetent(detent: RouteSheetDetent, rangePx: Float): Float =
        when (detent) {
            RouteSheetDetent.Collapsed -> 0f
            RouteSheetDetent.Expanded -> rangePx.coerceAtLeast(0f)
        }

    /** [revealPx] confined to the sheet's travel: never below collapsed, never above expanded. */
    fun clampReveal(revealPx: Float, rangePx: Float): Float =
        revealPx.coerceIn(0f, rangePx.coerceAtLeast(0f))

    /**
     * Which detent releasing at [revealPx] with [velocityPxPerSecond] settles to.
     *
     * Velocity is considered as well as distance, in both directions, so a flick
     * beats the halfway rule: thrown up expands from a short drag, thrown down
     * collapses even from past halfway (the user changed their mind mid-gesture).
     * Negative velocity is upward, matching Compose's pointer axis.
     *
     * A non-positive [rangePx] means there is nothing to reveal — no route yet,
     * a routing error, or a route with no steps — so the only honest answer is
     * collapsed, and no gesture can strand the sheet open over an empty list.
     */
    fun settleDetent(
        revealPx: Float,
        velocityPxPerSecond: Float,
        rangePx: Float,
    ): RouteSheetDetent {
        if (rangePx <= 0f) return RouteSheetDetent.Collapsed
        if (velocityPxPerSecond <= -FLING_VELOCITY_PX_PER_SECOND) return RouteSheetDetent.Expanded
        if (velocityPxPerSecond >= FLING_VELOCITY_PX_PER_SECOND) return RouteSheetDetent.Collapsed
        return if (revealPx >= rangePx * SETTLE_FRACTION) {
            RouteSheetDetent.Expanded
        } else {
            RouteSheetDetent.Collapsed
        }
    }

    /** The other detent — what tapping the drag handle switches to. */
    fun toggle(detent: RouteSheetDetent): RouteSheetDetent =
        when (detent) {
            RouteSheetDetent.Collapsed -> RouteSheetDetent.Expanded
            RouteSheetDetent.Expanded -> RouteSheetDetent.Collapsed
        }

    /**
     * How much of a scroll the SHEET takes BEFORE the step list sees it.
     *
     * Only the "finish opening it" direction: while the sheet is short of fully
     * expanded an upward drag opens the rest of it first, and only the leftover
     * reaches the list. Delegates to [PanelDrag.preScrollConsumption] with the
     * OUTSTANDING collapse (`rangePx - revealPx`) as its offset, which is the
     * same quantity that rule is written against — "how far from rest is it, and
     * how much of this drag puts it back".
     *
     * Returns 0 in every other case; in particular a DOWNWARD drag is never
     * taken pre-emptively, so scrolling the step list back up towards its first
     * maneuver always scrolls the list rather than collapsing the sheet.
     *
     * The returned value is a scroll delta (negative = upward), so applying it is
     * `reveal - taken`, exactly like [postScrollConsumption].
     */
    fun preScrollConsumption(availableY: Float, revealPx: Float, rangePx: Float): Float =
        PanelDrag.preScrollConsumption(
            availableY = availableY,
            offsetPx = (rangePx - revealPx).coerceAtLeast(0f),
        )

    /**
     * How much of a scroll the SHEET takes AFTER the step list had its turn.
     *
     * This is the whole list-vs-sheet resolution: a downward drag is offered to
     * the list first, so mid-list it simply scrolls and nothing is left over here
     * (`availableY` is 0 and the sheet does not move). Once the list is at its
     * top it can consume no more, the full delta arrives here unconsumed, and the
     * sheet starts to collapse. The user never has to think about which one they
     * are dragging.
     *
     * Clamped at [revealPx] — the shared [PanelDrag] rule is unbounded because
     * its panel is DISMISSIBLE and may keep going down off-screen, whereas this
     * sheet is a permanent bottom fixture that must stop exactly at its collapsed
     * peek and hand the rest of the gesture back.
     */
    fun postScrollConsumption(availableY: Float, revealPx: Float): Float =
        minOf(PanelDrag.postScrollConsumption(availableY), revealPx.coerceAtLeast(0f))

    /**
     * The sheet's COLLAPSED height, from the two heights a single layout pass
     * measures: the whole card and the revealed step-list area inside it.
     *
     * Derived rather than measured directly because the peek is not one
     * contiguous child — it is everything in the card except the list — and
     * because both inputs come from the same layout pass, so the result is stable
     * even mid-expansion (the card grows by exactly what the list grows by).
     *
     * This is what the map camera is told to keep clear (see the route-overlay
     * fit), so the whole route is visible above a COLLAPSED sheet.
     */
    fun collapsedHeightPx(sheetHeightPx: Int, stepsHeightPx: Int): Int =
        (sheetHeightPx - stepsHeightPx).coerceAtLeast(0)
}

/**
 * Sizing of the route-preview sheet that something OUTSIDE the sheet needs to
 * agree with — today the map camera, which has to leave the collapsed sheet's
 * worth of screen clear so the fitted route is not drawn underneath it.
 *
 * Separate from [RouteSheetDrag] (gesture arithmetic) because this is the
 * sheet's contract with the map, and it is read from the shell's map surface.
 */
object RouteSheetMetrics {
    /**
     * Tallest the revealed directions list is allowed to get, in dp.
     *
     * The list scrolls, so this caps how much SCREEN the expanded sheet claims
     * rather than how many maneuvers are reachable — a 40-turn motorway route is
     * fully readable at this height, it just scrolls.
     */
    const val STEPS_MAX_HEIGHT_DP = 260f

    /**
     * Fraction of the available height the revealed list may occupy, applied as
     * a second cap on [STEPS_MAX_HEIGHT_DP].
     *
     * Needed for SHORT windows (landscape, split-screen, a small foldable
     * cover): a fixed 260dp there is most of the screen and re-creates the
     * "directions take over more than half the screen" problem the sheet exists
     * to fix. On a normal portrait phone the fixed cap is the binding one.
     */
    const val STEPS_MAX_HEIGHT_FRACTION = 0.4f

    /**
     * Gap kept between the fitted route's lowest point and the top of the
     * collapsed sheet, so the route line ends visibly clear of the card instead
     * of tucked right against its edge.
     */
    const val CAMERA_CLEARANCE_DP = 24f

    /**
     * Bottom camera padding used before the sheet has been measured — the brief
     * window between picking a destination (marker drawn immediately) and the
     * sheet's first layout.
     *
     * Sized for a COLLAPSED sheet on a phone, which is what will appear. The
     * value this replaced was ~320dp: room for the old always-expanded sheet,
     * i.e. the camera used to reserve half the screen for a panel that now only
     * peeks.
     */
    const val COLLAPSED_FALLBACK_DP = 240f

    /** Height of the revealed step list for a window [availableHeightDp] tall. */
    fun stepsRevealHeightDp(availableHeightDp: Float): Float =
        minOf(STEPS_MAX_HEIGHT_DP, availableHeightDp * STEPS_MAX_HEIGHT_FRACTION)
            .coerceAtLeast(0f)

    /**
     * Device pixels of bottom padding the route camera fit must leave clear, for
     * a sheet measured at [collapsedSheetHeightPx] (0 = not measured yet) on a
     * display of [density].
     *
     * Always the COLLAPSED height, never the expanded one: the route is framed
     * for the state the sheet actually appears in, and expanding is a deliberate
     * "I want the turns now, not the map" — refitting the camera then would yank
     * the map out from under the user mid-drag.
     */
    fun cameraBottomPadPx(collapsedSheetHeightPx: Int, density: Float): Float =
        if (collapsedSheetHeightPx > 0) {
            collapsedSheetHeightPx + CAMERA_CLEARANCE_DP * density
        } else {
            (COLLAPSED_FALLBACK_DP + CAMERA_CLEARANCE_DP) * density
        }
}
