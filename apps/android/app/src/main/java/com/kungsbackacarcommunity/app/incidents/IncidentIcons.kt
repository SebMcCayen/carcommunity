package com.kungsbackacarcommunity.app.incidents

import androidx.annotation.DrawableRes
import com.kungsbackacarcommunity.app.R

/**
 * The category → glyph table for the incidents map layer.
 *
 * WHY THIS EXISTS: incidents used to be drawn as identical coloured dots, which
 * is unreadable in the one situation the layer is for — glancing at a moving map
 * while driving. Hue alone is not enough at 24dp on a basemap that is sometimes
 * light and sometimes dark, and it is nothing at all to the ~8% of men with a
 * red/green deficiency (the palette leans red/orange/amber, i.e. exactly the
 * axis they lose). So every category gets a distinct SILHOUETTE, and the colour
 * is a second, redundant channel rather than the only one:
 *
 * | category    | silhouette          | badge colour |
 * |-------------|---------------------|--------------|
 * | ACCIDENT    | impact burst + car  | red          |
 * | ROADWORK    | worker with shovel  | orange       |
 * | HAZARD      | warning triangle    | amber        |
 * | POLICE      | peaked police cap   | blue         |
 * | ROAD_CLOSED | barred stop octagon | purple       |
 *
 * Burst / human figure / triangle / cap / octagon share no outline with each
 * other, so the layer is still fully decodable in greyscale.
 *
 * EXHAUSTIVENESS IS THE POINT. [iconRes] is a `when` over the enum with NO
 * `else` branch and no default drawable, so adding a sixth [IncidentType]
 * without giving it a glyph is a COMPILE error rather than a marker that
 * silently renders as the blank dot this change set out to remove.
 */
object IncidentIcons {
    /** The white glyph drawn inside the category badge for [type]. */
    @DrawableRes
    fun iconRes(type: IncidentType): Int =
        when (type) {
            IncidentType.ACCIDENT -> R.drawable.ic_incident_accident
            IncidentType.ROADWORK -> R.drawable.ic_incident_roadwork
            IncidentType.HAZARD -> R.drawable.ic_incident_hazard
            IncidentType.POLICE -> R.drawable.ic_incident_police
            IncidentType.ROAD_CLOSED -> R.drawable.ic_incident_road_closed
        }
}

/**
 * The badge the glyph is drawn on, stated once so the map renderer and any
 * future in-app legend cannot drift apart.
 *
 * DAY *AND* NIGHT LEGIBILITY is a hard requirement here: the same marker has to
 * survive both the Standard style's bright day preset (pale grey roads, near-
 * white land) and its night preset (near-black). No single flat colour does
 * that, so the badge is built as a three-band target:
 *
 *  1. an outer near-black ring — separates the marker from the LIGHT basemap;
 *  2. a white ring inside it — separates the marker from the DARK basemap;
 *  3. the category fill, carrying the hue, with the glyph punched out in white.
 *
 * Whichever preset is active, one of the two rings is always in high contrast
 * against what is behind it, so the marker never dissolves into the map. That is
 * also why the amber HAZARD badge is safe on a light road: what touches the road
 * is the near-black ring, not the amber.
 *
 * Values are in dp and ARGB ints, sized for a marker read at arm's length in a
 * moving car — deliberately larger than the 9dp dot it replaces.
 */
object IncidentMarkerStyle {
    /** Full marker diameter, including both rings. */
    const val DIAMETER_DP: Float = 34f

    /** Width of the outer dark ring (the light-basemap separator). */
    const val OUTER_RING_DP: Float = 1.5f

    /** Width of the white ring (the dark-basemap separator). */
    const val WHITE_RING_DP: Float = 2.5f

    /** Fraction of the badge diameter the glyph occupies. */
    const val GLYPH_FRACTION: Float = 0.52f

    /** Outer ring colour: near-black rather than pure black, to read as a shadow. */
    const val OUTER_RING_COLOR: Int = 0xFF14181C.toInt()

    /** The mid ring / glyph colour. */
    const val WHITE: Int = 0xFFFFFFFF.toInt()
}
