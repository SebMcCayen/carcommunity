package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.location.LastKnownLocation
import com.kungsbackacarcommunity.app.navigation.NavInitialCamera

/** Browsing opens at the saved focus distance, before a new GPS fix arrives. */
object BrowsingInitialCamera {
    fun resolve(cached: LastKnownLocation?, browsingZoom: Double): MapCameraPosition =
        NavInitialCamera.resolve(cached).copy(
            zoom = MapZoomPreference.snap(
                browsingZoom.takeIf { it.isFinite() } ?: MapZoomPreference.DEFAULT_ZOOM,
            ),
        )
}
