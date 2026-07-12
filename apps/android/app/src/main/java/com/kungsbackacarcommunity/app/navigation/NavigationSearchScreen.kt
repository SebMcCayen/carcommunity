package com.kungsbackacarcommunity.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.MapPoint
import com.kungsbackacarcommunity.app.shell.MapRouteOverlay
import com.kungsbackacarcommunity.app.shell.MapSurface

/** Test tag on the whole navigation search overlay, for UI tests. */
const val NAV_SEARCH_TEST_TAG = "nav_search"

/** Test tag on the route preview's "Start" (turn-by-turn) button. */
const val NAV_START_TEST_TAG = "nav_start"

/**
 * Full-screen address-search + directions overlay behind the map-home "Where
 * to?" bar (Google-Maps style).
 *
 * The [mapSurface] renders full-bleed behind a floating search field. Typing
 * drives debounced autocomplete ([NavigationController]); picking a suggestion
 * fetches a driving route from the user's location, draws the destination marker
 * + route line on the surface, and shows a bottom sheet with the distance/ETA
 * summary and the step-by-step directions list.
 *
 * Fully guarded: with no Mapbox token the injected [searchClient] returns empty/
 * null (no network) and the surface is the stub, so this renders — search yields
 * "no results", nothing crashes — keeping the config-less/CI build green.
 *
 * Out of scope (follow-up): live turn-by-turn voice navigation, re-routing, and
 * lane guidance. This delivers search → route preview + a static directions list.
 *
 * @param originProvider current-location source for the route origin + proximity
 *   bias; returns null when unavailable (→ an inline "location off" hint).
 * @param onClose leave the overlay (back to the map-home tab).
 * @param onStartNavigation enter turn-by-turn navigation to the resolved
 *   destination (invoked from the route preview's "Start" button). Carries the
 *   destination coordinate + its label; the host supplies the origin/GPS.
 */
@Composable
fun NavigationSearchScreen(
    mapSurface: MapSurface,
    searchClient: MapboxSearchClient,
    originProvider: suspend () -> LatLng?,
    onClose: () -> Unit,
    onStartNavigation: (destination: LatLng, destinationLabel: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Keep the controller stable across recompositions (keyed only by the search
    // client) while always invoking the latest origin provider — otherwise a
    // changed originProvider lambda would be ignored and the stale one called.
    val currentOriginProvider by rememberUpdatedState(originProvider)
    val controller =
        remember(searchClient) {
            NavigationController(searchClient, { currentOriginProvider() }, scope)
        }
    val state by controller.state.collectAsState()

    // Fetch the origin up-front so the first suggestions are location-biased.
    LaunchedEffect(controller) { controller.refreshOrigin() }

    // Mirror the picked destination onto the map surface behind (cleared when
    // gone). The destination marker shows as soon as a destination is selected —
    // an empty path is a valid marker-only overlay (see MapRouteOverlay) — so the
    // marker is visible while the route is still loading or when routing fails
    // (NavError.NoOrigin / NavError.Route). The route line is added only once the
    // route has resolved.
    LaunchedEffect(state.destination, state.route) {
        val dest = state.destination
        val route = state.route
        mapSurface.setRouteOverlay(
            if (dest != null) {
                MapRouteOverlay(
                    destination = MapPoint(dest.point.longitude, dest.point.latitude),
                    path = route?.geometry?.map { MapPoint(it.longitude, it.latitude) } ?: emptyList(),
                )
            } else {
                null
            },
        )
    }

    // Back closes the route first (returns to search), then leaves the overlay.
    BackHandler {
        if (state.destination != null) controller.clearDestination() else onClose()
    }

    Box(modifier = modifier.fillMaxSize().testTag(NAV_SEARCH_TEST_TAG)) {
        // Full-bleed map behind (shows the drawn route + destination marker).
        mapSurface.Content(Modifier.fillMaxSize())

        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = KccSpacing.s3, vertical = KccSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            SearchField(
                query = state.query,
                onQueryChange = { new ->
                    // Editing while a destination is picked returns to search.
                    if (state.destination != null) controller.clearDestination()
                    controller.onQueryChange(new)
                },
                onBack = {
                    // Mirror the system BackHandler: a picked destination/route is
                    // closed first (return to search); only with none do we leave.
                    if (state.destination != null) controller.clearDestination() else onClose()
                },
                onClear = {
                    // Clearing (the X) fully returns to the search state: drop the
                    // picked destination + route first (like editing the query
                    // does), then wipe the query text so the sheet doesn't linger.
                    if (state.destination != null) controller.clearDestination()
                    controller.onQueryChange("")
                },
                searching = state.searching,
            )

            // Suggestions / hints only while still choosing a destination.
            if (state.destination == null) {
                when {
                    state.error == NavError.Search ->
                        HintCard(stringResource(R.string.addressSearch_searchError))
                    state.suggestions.isNotEmpty() ->
                        SuggestionsCard(
                            suggestions = state.suggestions,
                            onSelect = { controller.select(it) },
                        )
                    state.query.isNotBlank() && !state.searching ->
                        HintCard(stringResource(R.string.addressSearch_noResults))
                }
            }
        }

        // Bottom route sheet once a destination is picked.
        if (state.destination != null) {
            RouteSheet(
                state = state,
                onClear = { controller.clearDestination() },
                onStart = {
                    val dest = state.destination
                    if (dest != null) onStartNavigation(dest.point, dest.name)
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    searching: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Surface(
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.addressSearch_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                placeholder = {
                    Text(stringResource(R.string.addressSearch_searchPlaceholder))
                },
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
            )
            if (searching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).padding(end = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.addressSearch_clearQuery),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsCard(
    suggestions: List<PlaceSuggestion>,
    onSelect: (PlaceSuggestion) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(suggestions, key = { _, it -> it.id }) { index, suggestion ->
                if (index > 0) HorizontalDivider()
                // The whole row is the tap target.
                Surface(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    onClick = { onSelect(suggestion) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = suggestion.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (suggestion.address != null) {
                                Text(
                                    text = suggestion.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Surface(
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RouteSheet(
    state: NavUiState,
    onClear: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val destination = state.destination ?: return
    Surface(
        shape = RoundedCornerShape(topStart = KccRadius.xl, topEnd = KccRadius.xl),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = destination.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.addressSearch_clearRoute),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                state.routeLoading ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.addressSearch_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                state.error == NavError.NoOrigin ->
                    Text(
                        text = stringResource(R.string.addressSearch_noOrigin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                state.error == NavError.Route ->
                    Text(
                        text = stringResource(R.string.addressSearch_routeError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                state.route != null -> RouteDetails(route = state.route)
            }

            // Prominent "Start" CTA once a route is resolved — enters turn-by-turn
            // navigation (Google-Maps style). Only shown with a usable route.
            if (state.route != null) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().testTag(NAV_START_TEST_TAG),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.turnByTurn_start),
                        modifier = Modifier.padding(start = KccSpacing.s2),
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteDetails(route: RouteSummary) {
    val unitM = stringResource(R.string.addressSearch_unitMeters)
    val unitKm = stringResource(R.string.addressSearch_unitKilometers)
    val unitMin = stringResource(R.string.addressSearch_unitMinutes)
    val unitH = stringResource(R.string.addressSearch_unitHours)

    val distance = NavFormat.formatDistance(route.distanceMeters, unitM, unitKm)
    val eta = NavFormat.formatDuration(route.durationSeconds, unitMin, unitH)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            // Distance first, then duration/ETA — matching the app convention
            // (e.g. DrivesScreen): "4.5 km · 12 min".
            text = stringResource(R.string.addressSearch_routeSummary, distance, eta),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.addressSearch_directionsTitle),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
            itemsIndexed(route.steps) { index, step ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.DirectionsCar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = step.instruction,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (step.distanceMeters > 0.0) {
                        Text(
                            text = NavFormat.formatDistance(step.distanceMeters, unitM, unitKm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
