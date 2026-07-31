package com.kungsbackacarcommunity.app.billboards

/**
 * The geometry and palette of the sponsored-billboard map marker.
 *
 * ## Why this marker is a different SHAPE, not a different colour
 *
 * By the time billboards reach the map there are already three marker layers on
 * it, and **all three are circular discs**: incidents (a category glyph on a
 * category-coloured disc), Kronjakt crowns (a crown glyph on a rarity-coloured
 * disc, plus a halo on legendary), and community event pins (a calendar glyph
 * on a teal disc). Adding a fourth disc in a fourth colour would put four
 * meanings on one visual channel, and hue is the worst available channel for
 * it: roughly one man in twelve cannot separate the reds, oranges and purples
 * already in use, and nobody — colour-vision deficient or not — should be
 * parsing hue while driving.
 *
 * So the billboard marker is a **landscape plaque on a short post, anchored at
 * the foot of the post**. That differs from every existing marker in three
 * independent ways at once: silhouette (a rectangle, not a circle), aspect (a
 * wide panel, not a symmetrical badge), and anchoring (the marker stands ON its
 * point instead of being centred over it, so it sits visibly higher). Any one
 * of those survives a greyscale print; together they are unmistakable at a
 * glance, and they read identically to a colour-blind user because none of them
 * is a colour.
 *
 * The shape also SAYS what the thing is — it is the silhouette of a billboard —
 * which is the honest way to label a sponsored placement.
 *
 * ## Not a road sign
 *
 * Activation requires an admin to confirm the placement does not imitate a road
 * sign, so the marker itself must not undermine that. Swedish road signs are
 * yellow, blue or red, with hard corners, in triangles, circles and rectangles
 * with heavy borders. This is a rounded-cornered magenta panel on a post at
 * roughly a third of the size, carrying no pictogram — it does not resemble any
 * regulatory sign, and its colour is not in the regulatory palette.
 *
 * ## Legibility on both basemaps
 *
 * The same two-tone outline the incident and crown badges use, and for the same
 * reason: a dark hairline on the very outside carries the marker against the
 * light basemap, and a light ring just inside it carries the marker against the
 * dark one. One bitmap therefore works in both map modes, which matters here
 * because the day/night flip is a style change — there is no mechanism that
 * would swap in a second icon set.
 *
 * Pure constants (no Android graphics types), so the geometry is asserted in a
 * JVM unit test rather than only on a device. [BillboardMarkerBitmaps]
 * rasterises them.
 */
object BillboardMarkerStyle {
    /**
     * Plaque fill — a deep magenta.
     *
     * Chosen by ELIMINATION against every colour already on this map: incident
     * red `D32F2F`, orange `F57C00`, amber `FBC02D`, blue `1565C0` and purple
     * `7B1FA2`; crown grey `8E9AA6`, green `2E7D32`, deep purple `4527A0` and
     * gold `C79000`; event teal `00897B`. Magenta is the one unclaimed region
     * of the wheel, and it is also absent from Swedish road signage.
     *
     * Colour is nevertheless the LAST line of distinction here, not the first —
     * see this object's KDoc.
     */
    const val PLAQUE_COLOR: Int = 0xFFAD1457.toInt()

    /** The abstract "there is a message on this panel" bars, and the post. */
    const val PLAQUE_CONTENT_COLOR: Int = 0xFFFFFFFF.toInt()

    /** Plaque width. Landscape, so the panel reads as a panel and not a button. */
    const val PLAQUE_WIDTH_DP: Float = 30f

    /** Plaque height — a 3:2 panel. */
    const val PLAQUE_HEIGHT_DP: Float = 20f

    /** Corner radius. Rounded specifically so this cannot read as signage. */
    const val PLAQUE_CORNER_RADIUS_DP: Float = 4f

    /**
     * The post the plaque stands on, from the plaque's bottom edge down to the
     * anchor point. Long enough to be unmistakable in silhouette, short enough
     * that the plaque still sits close to the place it describes.
     */
    const val POST_HEIGHT_DP: Float = 8f

    /** Post width. */
    const val POST_WIDTH_DP: Float = 3.5f

    /**
     * The two content bars drawn inside the plaque, as fractions of the plaque's
     * inner width. Unequal on purpose: two equal bars read as a symbol, two
     * unequal ones read as text, which is what a billboard carries.
     */
    const val CONTENT_BAR_LONG_FRACTION: Float = 0.62f
    const val CONTENT_BAR_SHORT_FRACTION: Float = 0.4f

    /** Content-bar thickness as a fraction of the plaque's inner height. */
    const val CONTENT_BAR_THICKNESS_FRACTION: Float = 0.13f

    /** Vertical gap between the two content bars, as a fraction of inner height. */
    const val CONTENT_BAR_GAP_FRACTION: Float = 0.16f
}
