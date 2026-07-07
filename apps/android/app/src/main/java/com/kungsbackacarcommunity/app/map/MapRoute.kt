package com.kungsbackacarcommunity.app.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.R
import com.mapbox.common.MapboxOptions

/**
 * Map integration route (Phase 12 slice 7): sets the Mapbox access token (if
 * one is configured) then renders [MapScreen].
 *
 * ## Access-token guard (config-less CI)
 * A runtime Mapbox token is required to load tiles but is a secret NOT present
 * in CI. The token is read from the `mapbox_access_token` string resource,
 * which DEFAULTS to empty (see the debug `resValue` in app/build.gradle.kts).
 * We set [MapboxOptions.accessToken] only when it is non-blank; when blank the
 * MapView still renders (an empty style) rather than crashing, so both
 * `assembleDebug` and app launch stay green without a token. The real token is
 * provisioned at cutover (console/CI step).
 *
 * ## Marker scope
 * This slice centers the map on a default town-level camera and is wired to
 * draw the caller's OWN marker. A live own-position coordinate is not yet
 * exposed by the live seam (`LiveLocationRepository` observes session state,
 * not the latest fix), so [ownMarker] is null here for now; wiring the own
 * fix, and the multi-member feed (per-uid `liveLocation/{uid}/latest` reads),
 * are follow-ups.
 */
@Composable
fun MapRoute(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Read once per composition entry; empty by default (no token in CI).
    remember(context) {
        val token = context.getString(R.string.mapbox_access_token)
        if (token.isNotBlank()) {
            MapboxOptions.accessToken = token
        }
        token
    }

    MapScreen(ownMarker = MapMarkers.ownMarker(longitude = null, latitude = null), onBack = onBack)
}
