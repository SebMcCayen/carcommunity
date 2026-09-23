package com.kungsbackacarcommunity.app.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.live.LiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveMarker
import com.mapbox.common.MapboxOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Map integration route (Phase 12 slice 7 + live-markers follow-up): sets the
 * Mapbox access token (if one is configured), observes the live markers, then
 * renders [MapScreen].
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
 * ## Marker feed (per-uid reads, no collection scan)
 * The caller's OWN marker is read from `liveLocation/{uid}/latest` via
 * [LiveLocationRepository.observeLatest]. Other members' markers are the
 * [participantUids] (e.g. a group-drive roster) each observed the same per-uid
 * way and combined — there is NO collection scan (the RTDB rules grant per-uid
 * reads only, gated on active, non-suspended membership). Members who stopped
 * sharing emit null and are dropped by [MapMarkers.markers].
 *
 * When [repository] is null (Firebase unavailable) or [uid] is blank the map
 * still renders its default town camera with no markers.
 *
 * @param participantUids other members to show (own uid is de-duplicated).
 */
@Composable
fun MapRoute(
    repository: LiveLocationRepository?,
    uid: String,
    onBack: () -> Unit,
    participantUids: List<String> = emptyList(),
) {
    // Resolve the token in composition (Compose lint: resource values must be
    // read via Compose resource APIs, not LocalContext), then apply it as a
    // one-shot side effect; empty by default (no token in CI), in which case
    // the global token is left untouched.
    val token = stringResource(R.string.mapbox_access_token)
    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            MapboxOptions.accessToken = token
        }
    }

    // Own marker: per-uid read of the caller's own latest node.
    val ownFlow: Flow<LiveMarker?> =
        remember(repository, uid) {
            if (repository != null && uid.isNotBlank()) repository.observeLatest(uid) else flowOf(null)
        }
    val ownMarker by ownFlow.collectAsState(initial = null)

    // Other members: one per-uid flow each, combined into a single list. The
    // key includes the uid set so the combine is rebuilt when the roster
    // changes. Empty list short-circuits to a constant empty flow.
    val otherKey = participantUids.filter { it.isNotBlank() && it != uid }.distinct()
    val othersFlow: Flow<List<LiveMarker?>> =
        remember(repository, otherKey) {
            if (repository == null || otherKey.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(otherKey.map { memberUid -> repository.observeLatest(memberUid) }) {
                    it.toList()
                }
            }
        }
    val otherMarkers by othersFlow.collectAsState(initial = emptyList())

    MapScreen(
        markers = MapMarkers.markers(own = ownMarker, others = otherMarkers),
        onBack = onBack,
    )
}
