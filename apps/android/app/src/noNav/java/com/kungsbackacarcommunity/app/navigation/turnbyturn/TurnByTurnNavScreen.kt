package com.kungsbackacarcommunity.app.navigation.turnbyturn

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.incidents.ReportLocation
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.shell.MapIncidentMarker
import com.kungsbackacarcommunity.app.shell.MapProjection
import com.kungsbackacarcommunity.app.shell.MapQueryViewport

/**
 * Config-less / CI stub of the turn-by-turn navigation view.
 *
 * This is the implementation compiled when NO build-time Mapbox downloads token
 * is present (see app/build.gradle.kts `navSdkEnabled`): the Mapbox Navigation
 * SDK v3 is not on the classpath, so this file references none of it and simply
 * renders a themed "navigation unavailable" panel. Its signature is byte-for-byte
 * identical to the real [com.kungsbackacarcommunity.app.navigation.turnbyturn.TurnByTurnNavScreen]
 * in `src/nav`, so the rest of the app calls one entry point regardless of build.
 *
 * In practice a token-less build never reaches the "Start" button that opens
 * this screen (the route that precedes it needs a runtime Mapbox token to
 * resolve), so this is primarily a compile-time safety net that keeps CI green.
 *
 * @param origin resolved route origin (unused here; the real impl navigates from it).
 * @param destination the chosen destination coordinate.
 * @param destinationLabel human-readable destination name for the header.
 * @param onExit leave the navigation view.
 * @param onReportIncident report an incident/roadwork of the picked category
 *   (wired by the host to the shared `incidents-report` path; unused here).
 * @param incidentReportingEnabled whether reporting is offered (unused here —
 *   this stub owns no map and no control stack).
 * @param liveSessionBar the ongoing live-session pill (unused here; the real impl
 *   keeps it on screen during navigation). This screen renders no map chrome at
 *   all, so there is nowhere honest to put it.
 * @param convoyBar the convoy status bar slot (unused here; the real impl places
 *   it below the maneuver banner). This screen renders no map chrome at all, so
 *   there is nowhere honest to put it.
 * @param liveMembersOverlay the other-members-sharing-live layer (unused here for
 *   the same reason: no map, so no projection to draw it against).
 * @param incidentMarkers the incident badges the real screen draws on its own
 *   map (unused here: no map, so no layer to draw them on).
 * @param onQueryViewport where the real screen's map is looking, for the host's
 *   incident poll. Never invoked here — this stub owns no camera, so the host's
 *   existing shell-camera anchor simply stays in use.
 */
@Composable
fun TurnByTurnNavScreen(
    origin: LatLng?,
    destination: LatLng,
    destinationLabel: String,
    onExit: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onReportIncident: (IncidentType, ReportLocation) -> Unit,
    modifier: Modifier = Modifier,
    // Signature parity with the real src/nav screen (the host wires the back-key
    // confirm through here). Routed through BACK below so the stub behaves the
    // same way, though a token-less build never reaches a live route to guard.
    onBackPressed: () -> Unit = onExit,
    // Present ONLY to keep this stub's signature identical to the real src/nav
    // implementation, so the single host call site compiles in both builds.
    // Unused: this screen renders an "unavailable" panel and owns no map.
    @Suppress("UNUSED_PARAMETER") incidentReportingEnabled: Boolean = false,
    @Suppress("UNUSED_PARAMETER") incidentsLayerEnabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") onIncidentsLayerEnabledChange: (Boolean) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") incidentMarkers: List<MapIncidentMarker> = emptyList(),
    @Suppress("UNUSED_PARAMETER") onQueryViewport: ((MapQueryViewport?) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") trafikverketDataShown: Boolean = false,
    @Suppress("UNUSED_PARAMETER") trafficEnabled: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onTrafficEnabledChange: (Boolean) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") nightMode: Boolean? = null,
    @Suppress("UNUSED_PARAMETER") onNightModeChange: (Boolean) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") is3d: Boolean = true,
    @Suppress("UNUSED_PARAMETER") on3dEnabledChange: (Boolean) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") hasUnreadChat: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onOpenChat: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenSavedPlaces: () -> Unit = {},
    // Signature parity with the real src/nav screen (the crownHuntPerks-gated
    // perk-deploy control + its host callback). Unused: this stub owns no map.
    @Suppress("UNUSED_PARAMETER") crownHuntPerksEnabled: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onOpenPerks: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") liveSessionBar: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") convoyBar: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") liveMembersOverlay: (@Composable (MapProjection) -> Unit)? = null,
) {
    BackHandler { onBackPressed() }
    Box(
        modifier = modifier.fillMaxSize().padding(KccSpacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Text(
                text = destinationLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.turnByTurn_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onExit, modifier = Modifier.padding(top = KccSpacing.s2)) {
                Text(stringResource(R.string.turnByTurn_exit))
            }
        }
    }
}
