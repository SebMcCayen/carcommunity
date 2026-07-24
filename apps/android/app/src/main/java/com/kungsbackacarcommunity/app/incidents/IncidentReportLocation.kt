package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * WHERE a new incident report should be placed — the choice the report flow now
 * offers the user before it submits.
 *
 * The default/quick path stays [Current] (the device GPS fix the controller
 * resolves itself, exactly as reporting always worked); [Chosen] carries a
 * coordinate the user placed by hand on the map picker. Kept a small,
 * Android-free sealed type so the flow that maps a picker result to a report is
 * JVM-unit-testable without a device or a Mapbox surface.
 */
sealed interface ReportLocation {
    /** Report at the caller's CURRENT device location (the default, quick path). */
    data object Current : ReportLocation

    /** Report at a [location] the user placed on the map picker. */
    data class Chosen(val location: LatLng) : ReportLocation
}

/**
 * Whether [location] is a sane WGS-84 coordinate the report flow may submit.
 *
 * Mirrors the backend's own `isValidCoordinate` guard (finite, latitude in
 * [-90, 90], longitude in [-180, 180]) so the client rejects an impossible
 * picked point BEFORE the round-trip rather than letting the callable answer
 * `invalid-argument`. A picker that hands back a NaN centre (no style/camera yet)
 * or an out-of-range value therefore never reaches the wire.
 */
fun isValidReportCoordinate(location: LatLng): Boolean {
    val lat = location.latitude
    val lng = location.longitude
    return lat.isFinite() &&
        lng.isFinite() &&
        lat in MIN_LATITUDE..MAX_LATITUDE &&
        lng in MIN_LONGITUDE..MAX_LONGITUDE
}

private const val MIN_LATITUDE = -90.0
private const val MAX_LATITUDE = 90.0
private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0
