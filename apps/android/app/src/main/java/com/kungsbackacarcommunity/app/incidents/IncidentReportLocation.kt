package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.isValidWgs84Coordinate

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
 * A thin, incident-named alias over the shared [isValidWgs84Coordinate] so this
 * flow and convoy destinations cannot drift apart on what counts as a sendable
 * point. Matches the backend's `reportIncident` input schema (finite — Zod
 * rejects NaN — latitude in [-90, 90], longitude in [-180, 180]), so the client
 * rejects an impossible picked point BEFORE the round-trip rather than letting
 * the callable answer `invalid-argument`. A picker that hands back a NaN centre
 * (no style/camera yet) or an out-of-range value never reaches the wire.
 */
fun isValidReportCoordinate(location: LatLng): Boolean =
    isValidWgs84Coordinate(latitude = location.latitude, longitude = location.longitude)
