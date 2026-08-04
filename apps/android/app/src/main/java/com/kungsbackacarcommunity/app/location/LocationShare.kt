package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * A location the user wants to share with a friend or persist as a saved place —
 * a display [name] plus its [point]. Deliberately tiny and Android-free so the
 * share flow (friend picker → DM send) and the naming popup share ONE model and
 * the naming/message-building rules are JVM-unit-testable off-Compose.
 *
 * @param name what to call the place. May be blank at the point a popup is opened
 *   (the user has not typed a name yet); [LocationShare.resolveName] fills a blank
 *   in from the coordinate so a place is never nameless.
 * @param point the WGS-84 coordinate, in the app's lng-first [LatLng] ordering.
 */
data class ShareableLocation(
    val name: String,
    val point: LatLng,
)

/**
 * Pure naming + message-building rules for saving and sharing a location, shared
 * by the map's "Save this location" naming popup, the saved-places share action,
 * and any other location-share entry point.
 *
 * There is no new backend here: a shared location is delivered as an ordinary
 * direct message whose body carries a [GeoLinks] `geo:` token, which every chat
 * surface already detects and renders as a tappable "show on map" chip (see
 * `GeoLinks.findAll`). So "share a place with a friend" reuses the existing
 * `dm-sendMessage` send path verbatim — this object only decides the TEXT.
 */
object LocationShare {
    /**
     * The readable "lat, lng" name a place falls back to when the user leaves the
     * name blank — the SAME rounding/format the map's place menu and the clipboard
     * writer use ([GeoLinks.coordinateLabel]), so a coordinate reads identically
     * wherever it appears.
     */
    fun coordinateName(point: LatLng): String =
        GeoLinks.coordinateLabel(latitude = point.latitude, longitude = point.longitude)

    /**
     * The name to persist/share for [point]: the user's [rawName] trimmed, or —
     * when they typed nothing — the coordinate string, so a place saved or shared
     * from the map is never nameless. This is the "empty name → GPS position"
     * rule, kept here (not in `SavedPlaces.create`, whose blank-label fallback is
     * the place's own geocoded name for the search flow) so the map naming popup
     * owns its own coordinate fallback without changing the search-save default.
     */
    fun resolveName(rawName: String, point: LatLng): String =
        rawName.trim().ifBlank { coordinateName(point) }

    /**
     * The direct-message body for sharing [name] at [point]: the resolved name on
     * its own line, then the `geo:` token (with a leading 📍 so it reads as a place
     * even before it is linkified). The recipient's chat detects the token and
     * turns it into a tappable chip that moves the app's map to the point.
     */
    fun messageText(name: String, point: LatLng): String {
        val resolved = resolveName(name, point)
        return "$resolved\n${GeoLinks.formatForClipboard(point.latitude, point.longitude)}"
    }
}
