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
import com.kungsbackacarcommunity.app.navigation.LatLng

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
 * @param onReportIncident report an incident/roadwork (wired by the host).
 * @param isLiveSharing whether a live-location session is running (unused here;
 *   the real impl keeps the live control on screen while driving).
 * @param canShareLive whether the caller may start a session (unused here).
 * @param onStartLiveShare start a live-sharing session (unused here).
 * @param onHideMeNow the privacy stop (unused here).
 * @param onOpenLiveShareDetails open the full live-location screen (unused here).
 */
@Composable
fun TurnByTurnNavScreen(
    origin: LatLng?,
    destination: LatLng,
    destinationLabel: String,
    onExit: () -> Unit,
    onReportIncident: () -> Unit,
    modifier: Modifier = Modifier,
    // Present ONLY to keep this stub's signature identical to the real src/nav
    // implementation, so the single host call site compiles in both builds.
    // Unused: this screen renders an "unavailable" panel and owns no map.
    @Suppress("UNUSED_PARAMETER") isLiveSharing: Boolean = false,
    @Suppress("UNUSED_PARAMETER") canShareLive: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onStartLiveShare: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onHideMeNow: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenLiveShareDetails: () -> Unit = {},
) {
    BackHandler { onExit() }
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
