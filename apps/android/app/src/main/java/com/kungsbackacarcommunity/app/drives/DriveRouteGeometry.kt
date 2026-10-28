package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.navigation.LatLng

/** Map input preserves provenance. Simplified geometry has no timestamps or derived statistics. */
sealed interface DriveRouteGeometry {
    val coordinates: List<LatLng>

    data class Recorded(val points: List<RoutePoint>) : DriveRouteGeometry {
        override val coordinates = points.map { LatLng(latitude = it.latitude, longitude = it.longitude) }
    }

    data class Simplified(override val coordinates: List<LatLng>) : DriveRouteGeometry
}

/** Chooses drawable geometry without changing the full-route load result or bypassing denial. */
internal fun driveRouteGeometry(
    replay: RouteReplayState,
    encodedThumbnail: String?,
    hasOwner: Boolean,
): DriveRouteGeometry? {
    if (!hasOwner || replay is RouteReplayState.Loading) return null
    if (replay is RouteReplayState.Ready && replay.points.size >= 2) {
        return DriveRouteGeometry.Recorded(replay.points)
    }
    val failure = (replay as? RouteReplayState.Unavailable)?.reason
    if (failure == RouteReplayFailure.SIGN_IN_REQUIRED ||
        failure == RouteReplayFailure.ACCESS_DENIED ||
        failure == RouteReplayFailure.NOT_FOUND
    ) return null
    val coordinates = RouteThumbnail.decode(encodedThumbnail)
    // Reuse the thumbnail's bounds/finite/25 m extent checks before giving any
    // stored polyline to Mapbox. No RoutePoint or synthetic clock is constructed.
    if (RouteThumbnail.project(coordinates, 88f, 64f, 6f) == null) return null
    return DriveRouteGeometry.Simplified(coordinates)
}

/** Statistics can only come from actual full recorded fixes, never map fallback geometry. */
internal fun routeReplayTopSpeed(replay: RouteReplayState): Double? =
    (replay as? RouteReplayState.Ready)?.points?.let { DriveSummary.topSpeedMetersPerSecond(it) }
