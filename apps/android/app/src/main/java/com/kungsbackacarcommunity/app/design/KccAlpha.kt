package com.kungsbackacarcommunity.app.design

/**
 * Aero translucency alphas — the single source of truth for how see-through the
 * app's floating "frosted glass" surfaces are, so tuning the look is a one-line
 * edit rather than hunting duplicated magic numbers.
 *
 * This lives in the neutral `design` package (not `shell` or `incidents`) so any
 * feature can import it without creating a cross-file dependency cycle.
 */
object KccAlpha {
    /**
     * Surface opacity for the Aero floating surfaces: the map-overlay popups
     * (layers, live-location, and the incident-report type picker) and the
     * translucent shell panels (the chat hub card, etc.). Slightly translucent
     * so the live map / content behind shows through a little and every floating
     * surface reads as one consistent layer, while staying opaque enough that
     * body text stays legible over moving roads.
     */
    val aeroSurface = 0.92f
}
