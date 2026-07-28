package com.kungsbackacarcommunity.app.navigation.turnbyturn

/**
 * The ARGB colours the turn-by-turn route line is drawn with.
 *
 * @property remaining the road you still have to drive — the whole point of the
 *   layer, and the one colour that has to be unmistakable at a glance.
 * @property remainingCasing the darker outline drawn under [remaining], which is
 *   what keeps the line legible where it crosses a road of a similar hue.
 * @property traveled the part of the route already behind you, deliberately
 *   de-emphasised rather than hidden: it still says "this was the route", it
 *   just stops competing with the road ahead.
 * @property traveledCasing outline for [traveled].
 * @property closure a road closed on the route (the SDK's own closure class).
 * @property restricted a restricted section (bus lane, permit-only) on the route.
 */
data class NavRouteLineColors(
    val remaining: Int,
    val remainingCasing: Int,
    val traveled: Int,
    val traveledCasing: Int,
    val closure: Int,
    val restricted: Int,
)

/**
 * Day and night palettes for the turn-by-turn route line.
 *
 * ## Why this exists at all
 * Seb reported seeing the maneuver ARROW but no coloured road. The route-line
 * renderer was wired but its draw data could be dropped before the style
 * existed (see `TurnByTurnEngine.loadStyleAndInit`), and it was left on the
 * SDK's stock colours — which are tuned for Mapbox's own navigation styles, not
 * for a map that also carries our traffic ramp, incident badges, breadcrumb
 * tail, convoy markers, event pins and crowns. Naming the colours here makes
 * them reviewable and lets `NavRouteLineColorsTest` assert the one property that
 * actually matters: that the route cannot be confused with anything else already
 * on the map.
 *
 * ## Why these hues
 * The remaining route is a saturated BLUE-VIOLET. Every other line-like thing on
 * this map is somewhere else on the wheel, and deliberately so:
 * - the traffic congestion ramp is green → amber → orange → red
 *   ([com.kungsbackacarcommunity.app.shell.TrafficPalette]),
 * - the breadcrumb tail is the brand gold `#EAB54B`,
 * - the destination dot and the closure colour are red,
 * - convoy markers ring `primary`, nearby sharers ring `tertiary`.
 *
 * A blue-violet route therefore reads as "the road I am supposed to drive" and
 * never as traffic, as a hazard, or as where I have already been. It is also the
 * one colour a driver is culturally primed to read as "the route".
 *
 * The night palette is not the day palette dimmed: on the dark navigation
 * basemap a dimmed blue disappears into the asphalt, so night uses a LIGHTER,
 * slightly cyan-shifted blue with a much darker casing to hold its edge.
 *
 * ## One continuous ribbon, NOT a congestion ramp
 * The SDK colours the route per segment by congestion class by default. That is
 * deliberately turned OFF: `TurnByTurnNavScreen.buildRouteLineView` sets every
 * congestion class — low, moderate, heavy, severe and unknown — to
 * [NavRouteLineColors.remaining], so the road you must drive reads as one
 * unbroken line. A green/amber/red ramp
 * would both break that line up and duplicate the map's own traffic overlay,
 * which is a separate layer the driver can switch on from the nav screen. The
 * overlay is where congestion IS colour-coded; `NavRouteLineColorsTest` asserts
 * the route stays far from every band of it, precisely so the two can never be
 * confused for one another.
 *
 * [NavRouteLineColors.closure] and [NavRouteLineColors.restricted] do keep their
 * own colours: they change whether you may use the road at all, which is a
 * different question from how busy it is.
 *
 * ## Explicitly NOT speed
 * Nothing here is derived from how fast the driver is going, and there is no
 * hook to make it so. Every colour above is a property of the ROAD.
 */
object NavRouteLinePalette {
    /**
     * Day: a strong royal blue on a light basemap, with a deep navy casing.
     */
    val DAY =
        NavRouteLineColors(
            remaining = 0xFF2F6BE0.toInt(),
            remainingCasing = 0xFF17408C.toInt(),
            // Grey-blue at partial weight: visible as "the way you came", but
            // clearly subordinate to the road ahead.
            traveled = 0xFF98A6BC.toInt(),
            traveledCasing = 0xFF7A879B.toInt(),
            closure = 0xFFD32F2F.toInt(),
            restricted = 0xFF6B7280.toInt(),
        )

    /**
     * Night: a lighter, cyan-shifted blue that survives the dark navigation
     * basemap, with a near-black casing so it still has an edge.
     */
    val NIGHT =
        NavRouteLineColors(
            remaining = 0xFF4FA3FF.toInt(),
            remainingCasing = 0xFF0B2A5B.toInt(),
            traveled = 0xFF54607A.toInt(),
            traveledCasing = 0xFF39445C.toInt(),
            closure = 0xFFE65656.toInt(),
            restricted = 0xFF6B7280.toInt(),
        )

    /** The palette for the current basemap. */
    fun forNight(night: Boolean): NavRouteLineColors = if (night) NIGHT else DAY
}
