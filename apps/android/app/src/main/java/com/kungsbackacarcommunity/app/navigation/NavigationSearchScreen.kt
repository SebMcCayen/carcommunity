package com.kungsbackacarcommunity.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.MapPoint
import com.kungsbackacarcommunity.app.shell.MapRouteOverlay
import com.kungsbackacarcommunity.app.shell.MapSurface
import com.kungsbackacarcommunity.app.shell.PanelDragHandle
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Test tag on the whole navigation search overlay, for UI tests. */
const val NAV_SEARCH_TEST_TAG = "nav_search"

/** Test tag on the route preview's "Start" (turn-by-turn) button. */
const val NAV_START_TEST_TAG = "nav_start"

/** Test tag on the bottom route-preview sheet itself. */
const val NAV_ROUTE_SHEET_TEST_TAG = "nav_route_sheet"

/** Test tag on the route-preview sheet's drag handle (expand/collapse). */
const val NAV_ROUTE_SHEET_HANDLE_TEST_TAG = "nav_route_sheet_handle"

/** Test tag on the route-preview sheet's revealed step-by-step directions area. */
const val NAV_ROUTE_STEPS_TEST_TAG = "nav_route_steps"

/** Test tag on the "set this place as the convoy's shared destination" action. */
const val NAV_CONVOY_DESTINATION_TEST_TAG = "nav_convoy_destination"

/** Test tag on the saved-places card in the empty search state. */
const val NAV_SAVED_TEST_TAG = "nav_saved"

/** Test tag on the route preview's save/edit-saved-place button. */
const val NAV_SAVE_TEST_TAG = "nav_save"

/** Test tag on the save dialog's confirm button. */
const val NAV_SAVE_CONFIRM_TEST_TAG = "nav_save_confirm"

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
 *   destination coordinate + its label; the host decides HOW to navigate — the
 *   in-app Mapbox turn-by-turn screen when the Navigation SDK is bundled, else a
 *   handoff to the device's maps app (see the host wiring).
 * @param recentStore persistence for recently selected places, shown in the
 *   empty (pre-typing) search state for one-tap re-selection. Null (the default)
 *   uses an in-memory store created once via [remember] — see the resolution
 *   below for why the fallback must be stable across recompositions.
 * @param savedStore persistence for the user's saved places (Home/Work/
 *   favourites), shown above the recents for one-tap routing and written from
 *   the route preview's save button. Local + synchronous, so the shortcuts work
 *   offline. Null (the default) uses a stable in-memory store, same as above.
 * @param initialTarget a raw coordinate to preview immediately on open (a map
 *   "navigate here" gesture — a long-press on open map, or a tap on a place the
 *   basemap draws): it is fed into the same route preview + Start flow as a
 *   searched place. Null for a normal search-first open.
 * @param initialTargetName the name of [initialTarget] when the gesture already
 *   knew it — a tapped shop/workshop/petrol station carries the basemap's own
 *   label, so the preview can name the destination instead of calling it a
 *   dropped pin. Null (a long-press on open map, where there is nothing to name)
 *   falls back to reverse-geocoding for a label. Ignored without [initialTarget].
 * @param onSetAsConvoyDestination when non-null, the route preview gains a second
 *   action: "set this place as the CONVOY's shared destination". This is how the
 *   convoy bar picks a destination — deliberately reusing this one search + saved
 *   places + recents + map-long-press flow rather than growing a second place
 *   picker that would have its own geocoding, its own recents and its own bugs.
 *   Null (the default) for a normal navigate-only open, i.e. whenever the user is
 *   not in a convoy.
 * @param convoyDestinationEnabled whether that second action can actually run.
 *   False while `convoy-setDestination` does not exist
 *   (`ConvoyDestinations.availability`), which renders the button disabled rather
 *   than absent — same honesty rule as the convoy bar's own controls.
 * @param initialSaveEdit set when this overlay was opened to CHANGE the address of
 *   an already-saved place (the Saved-places screen's "Change address"). It
 *   pre-frames the save dialog to that place's kind (so re-pointing Home saves
 *   back as Home, not a new Favourite) and label, and — for a favourite whose id
 *   moves with its address — sweeps the stale row. Null (the default) for a normal
 *   search-first open, where a fresh save defaults to a Favourite.
 */
@Composable
fun NavigationSearchScreen(
    mapSurface: MapSurface,
    searchClient: MapboxSearchClient,
    originProvider: suspend () -> LatLng?,
    onClose: () -> Unit,
    onStartNavigation: (destination: LatLng, destinationLabel: String) -> Unit,
    recentStore: RecentSearchesStore? = null,
    savedStore: SavedPlacesStore? = null,
    initialTarget: LatLng? = null,
    initialTargetName: String? = null,
    onSetAsConvoyDestination: ((LatLng, String) -> Unit)? = null,
    convoyDestinationEnabled: Boolean = false,
    initialSaveEdit: SavedPlaceEdit? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Dismiss the soft keyboard + drop text-field focus when a place is picked.
    // Selecting a suggestion swaps the search field for the bottom route sheet
    // (distance/ETA + the step-by-step directions); if the IME stayed up it would
    // sit ON TOP of that sheet and hide the directions. We hide it the moment a
    // result is chosen so the steps are fully visible.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Resolve the recent-searches store to a STABLE instance. When the caller
    // omits one, build the in-memory fallback once with remember instead of a
    // default-arg `InMemoryRecentSearchesStore()`, which would be a fresh object
    // on every recomposition. Because the store is a remember() key for the
    // controller below, an unstable fallback would recreate the
    // NavigationController on each recomposition — restarting its effects and
    // dropping the in-flight debounced search — so typed suggestions would never
    // surface.
    val resolvedRecentStore = remember(recentStore) { recentStore ?: InMemoryRecentSearchesStore() }
    // Same stability requirement as the recents store above: an unstable fallback
    // would be a fresh remember() key each recomposition and recreate the
    // controller, dropping the in-flight debounced search.
    val resolvedSavedStore = remember(savedStore) { savedStore ?: InMemorySavedPlacesStore() }
    // Keep the controller stable across recompositions (keyed on the search
    // client + stores) while always invoking the latest origin provider —
    // otherwise a changed originProvider lambda would be ignored and the stale
    // one called.
    val currentOriginProvider by rememberUpdatedState(originProvider)
    val controller =
        remember(searchClient, resolvedRecentStore, resolvedSavedStore) {
            NavigationController(
                searchClient,
                { currentOriginProvider() },
                scope,
                resolvedRecentStore,
                resolvedSavedStore,
            )
        }
    val state by controller.state.collectAsState()

    // The save/edit dialog's target, null when closed. Set from the route
    // preview's save button (a new or already-saved destination) and from a
    // saved row's edit button in the empty search state.
    var saveTarget by remember { mutableStateOf<PlaceSuggestion?>(null) }

    // The pending "change address" re-point, consumed by the FIRST save so a
    // second save in the same session is a normal fresh Favourite again. Re-armed
    // (via the remember key) if the host passes a new edit. See [initialSaveEdit].
    var pendingEdit by remember(initialSaveEdit) { mutableStateOf(initialSaveEdit) }

    // Pick a place (suggestion or recent) AND dismiss the keyboard first, so the
    // route sheet's directions aren't left behind the IME (see above).
    val onSelectPlace: (PlaceSuggestion) -> Unit = { place ->
        keyboardController?.hide()
        focusManager.clearFocus()
        controller.select(place)
    }

    // Fetch the origin up-front so the first suggestions are location-biased.
    LaunchedEffect(controller) { controller.refreshOrigin() }

    // A map "navigate here" gesture: immediately preview + route to the requested
    // coordinate through the same flow as a searched place. A tapped place brings
    // its own name; a long-press on open map has none, so it is reverse-geocoded
    // for a label and falls back to "dropped pin". Keyed on the target AND its
    // name so a new gesture re-previews.
    val droppedPinLabel = stringResource(R.string.addressSearch_droppedPin)
    LaunchedEffect(controller, initialTarget, initialTargetName) {
        if (initialTarget != null) {
            controller.selectPoint(
                point = initialTarget,
                fallbackLabel = droppedPinLabel,
                knownName = initialTargetName,
            )
        }
    }

    // The route sheet's COLLAPSED height, reported by the sheet once measured (0
    // until then). The camera fit below reserves exactly this much screen, so the
    // whole route is framed above the sheet in the state it actually appears in —
    // collapsed. Before the sheet fixed itself to a peek this reservation was a
    // fixed ~320dp of "expanded sheet", i.e. the map framed the route into the
    // top half of the screen and the sheet then covered the rest.
    var collapsedSheetHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // Mirror the picked destination onto the map surface behind (cleared when
    // gone). The destination marker shows as soon as a destination is selected —
    // an empty path is a valid marker-only overlay (see MapRouteOverlay) — so the
    // marker is visible while the route is still loading or when routing fails
    // (NavError.NoOrigin / NavError.Route). The route line is added only once the
    // route has resolved.
    //
    // Also keyed on the measured collapsed height, so the fit is REDONE the moment
    // the sheet reports its real size: the first overlay (drawn the instant a
    // destination is picked, before any layout pass) uses the phone-sized fallback
    // and is corrected one frame later, rather than being stuck at a guess.
    LaunchedEffect(state.destination, state.route, collapsedSheetHeightPx) {
        val dest = state.destination
        val route = state.route
        mapSurface.setRouteOverlay(
            if (dest != null) {
                MapRouteOverlay(
                    destination = MapPoint(dest.point.longitude, dest.point.latitude),
                    path = route?.geometry?.map { MapPoint(it.longitude, it.latitude) } ?: emptyList(),
                    bottomInsetPx =
                        RouteSheetMetrics.cameraBottomPadPx(
                            collapsedSheetHeightPx = collapsedSheetHeightPx,
                            density = density.density,
                        ),
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

    // Chrome only — the map behind this (showing the drawn route + destination
    // marker) is the shell's single surface, composed once underneath every page.
    // This deliberately does NOT call [MapSurface.Content]: a second MapView call
    // site here meant opening the search DISPOSED the map home's map and built a
    // fresh one — a full style load with nothing on screen until its first GL
    // frame, i.e. the white flash on entering AND leaving the search. The Box
    // stays transparent so that map shows through.
    Box(modifier = modifier.fillMaxSize().testTag(NAV_SEARCH_TEST_TAG)) {
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
                            onSelect = onSelectPlace,
                        )
                    // Empty query (search bar just opened): offer the saved places
                    // and the last few selected ones for one-tap re-selection.
                    state.query.isBlank() &&
                        (state.savedPlaces.isNotEmpty() || state.recents.isNotEmpty()) ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                        ) {
                            // Saved places sit ABOVE recents: they are the user's
                            // deliberate shortlist, so Home/Work stay reachable in
                            // the same spot instead of being pushed around by
                            // whatever was searched most recently.
                            if (state.savedPlaces.isNotEmpty()) {
                                SavedPlacesCard(
                                    saved = state.savedPlaces,
                                    onSelect = { onSelectPlace(it.place) },
                                    onEdit = { saveTarget = it.place },
                                )
                            }
                            if (state.recents.isNotEmpty()) {
                                RecentsCard(
                                    recents = state.recents,
                                    onSelect = onSelectPlace,
                                )
                            }
                        }
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
                onSave = { saveTarget = state.destination },
                onSetAsConvoyDestination =
                    onSetAsConvoyDestination?.let { setForConvoy ->
                        {
                            val dest = state.destination
                            if (dest != null) setForConvoy(dest.point, dest.name)
                        }
                    },
                convoyDestinationEnabled = convoyDestinationEnabled,
                onCollapsedHeightChanged = { collapsedSheetHeightPx = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // Save / edit dialog, hosted at the top level so it survives the sheet vs.
    // the saved-row it was opened from. The existing entry (if any) is resolved
    // from live state, so the dialog opens in "edit" mode — pre-filled label +
    // kind, with a Remove action — for a place that is already saved.
    val target = saveTarget
    if (target != null) {
        SavePlaceDialog(
            place = target,
            existing = SavedPlaces.find(state.savedPlaces, target),
            pendingEdit = pendingEdit,
            onDismiss = {
                // Abandoning the dialog abandons the re-point: consume it so a
                // later save this session is a fresh place, not a stale change-
                // address that would default to the old kind and sweep its id
                // (e.g. remove "home" when saving an unrelated favourite).
                pendingEdit = null
                saveTarget = null
            },
            onSave = { kind, label ->
                // Sweep the old row FIRST (before adding the new one) so the brief
                // over-cap moment can't make upsert evict an unrelated favourite.
                // Null unless this is a re-point whose id moved — Home/Work keep
                // their id and are updated in place by savePlace. See [sweepIdFor].
                SavedPlaces.sweepIdFor(pendingEdit, target, kind)
                    ?.let { controller.removeSavedPlace(it) }
                controller.savePlace(target, kind, label)
                // Consume the re-point: a later save this session is a fresh place.
                pendingEdit = null
                saveTarget = null
            },
            onRemove = { id ->
                controller.removeSavedPlace(id)
                // Removing here also concludes any pending re-point, so it can't
                // be re-applied to an unrelated later save. See onDismiss above.
                pendingEdit = null
                saveTarget = null
            },
        )
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

/**
 * The user's saved places in the empty search state. Tapping a row selects it as
 * the destination — the identical [NavigationController.select] path a search
 * result takes — so a saved place is genuinely one tap from a route preview. The
 * trailing edit button opens the same dialog the route sheet's save button does,
 * pre-filled, for rename / re-kind / remove.
 *
 * Renders [saved] in full, deliberately: the store caps at [SavedPlaces.MAX] and
 * that cap *is* what fits here, so there is nothing to truncate and no hidden
 * remainder. Truncating anyway would silently re-introduce the saved-but-
 * invisible places the single cap exists to rule out.
 */
@Composable
private fun SavedPlacesCard(
    saved: List<SavedPlace>,
    onSelect: (SavedPlace) -> Unit,
    onEdit: (SavedPlace) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().testTag(NAV_SAVED_TEST_TAG),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.addressSearch_savedTitle),
                modifier =
                    Modifier.padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            saved.forEachIndexed { index, place ->
                if (index > 0) HorizontalDivider()
                val label = place.displayLabel()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The row (minus the edit button) is the tap target → route.
                    Surface(
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        onClick = { onSelect(place) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier =
                                Modifier.padding(
                                    start = KccSpacing.s4,
                                    top = KccSpacing.s3,
                                    bottom = KccSpacing.s3,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                        ) {
                            Icon(
                                imageVector = place.kind.icon(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Under a Home/Work shortcut the secondary line is
                                // the address it points at — otherwise the row
                                // would read just "Home" with no way to tell WHICH
                                // address it will route to.
                                val subtitle = place.place.address ?: place.place.name
                                if (subtitle != label) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { onEdit(place) }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription =
                                stringResource(R.string.addressSearch_savedEdit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Save / edit dialog for a destination: pick the kind (Home / Work / Favourite),
 * name it, save. Opened either from the route preview's save button or from a
 * saved row's edit button; [existing] non-null puts it in edit mode — pre-filled
 * and with a Remove action.
 *
 * The label field is hidden for the singletons: their display name is the
 * localized "Home"/"Work", so asking for a name there would offer text that is
 * then never shown (see [displayLabel]).
 */
@Composable
private fun SavePlaceDialog(
    place: PlaceSuggestion,
    existing: SavedPlace?,
    pendingEdit: SavedPlaceEdit?,
    onDismiss: () -> Unit,
    onSave: (SavedPlaceKind, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    // A pending re-point ("Change Home address") pre-selects that kind + label, so
    // the dialog opens on Home rather than the Favourite default; otherwise it
    // falls back to an existing match, then to a fresh Favourite (see
    // [SavedPlaces.resolveSaveDefaults]).
    val defaults = SavedPlaces.resolveSaveDefaults(place, existing, pendingEdit)
    var kind by remember(existing, pendingEdit) { mutableStateOf(defaults.first) }
    var label by remember(existing, pendingEdit) { mutableStateOf(defaults.second) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing != null) {
                        R.string.addressSearch_savedDialogEditTitle
                    } else {
                        R.string.addressSearch_savedDialogTitle
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                // The address being saved, so the user can tell what this shortcut
                // will point at before naming it.
                Text(
                    text = place.address ?: place.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.addressSearch_savedKindLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                    SavedPlaceKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = {
                                Text(
                                    stringResource(
                                        when (option) {
                                            SavedPlaceKind.Home -> R.string.addressSearch_savedHome
                                            SavedPlaceKind.Work -> R.string.addressSearch_savedWork
                                            SavedPlaceKind.Favourite ->
                                                R.string.addressSearch_savedFavourite
                                        },
                                    ),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = option.icon(),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
                if (kind == SavedPlaceKind.Favourite) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(SavedPlaces.MAX_LABEL) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.addressSearch_savedLabelHint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(kind, label) },
                modifier = Modifier.testTag(NAV_SAVE_CONFIRM_TEST_TAG),
            ) {
                Text(stringResource(R.string.addressSearch_savedSave))
            }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { onRemove(existing.id) }) {
                        Text(
                            text = stringResource(R.string.addressSearch_savedRemove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.addressSearch_savedCancel))
                }
            }
        },
    )
}

@Composable
private fun RecentsCard(
    recents: List<PlaceSuggestion>,
    onSelect: (PlaceSuggestion) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.addressSearch_recentTitle),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Rendered in full: the store caps at RecentSearches.MAX, and there
            // is no "show all", so everything held is everything shown.
            recents.forEachIndexed { index, place ->
                if (index > 0) HorizontalDivider()
                // The whole row is the tap target (re-selects → route preview).
                Surface(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    onClick = { onSelect(place) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = place.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (place.address != null) {
                                Text(
                                    text = place.address,
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

/**
 * The bottom route-preview sheet: a bottom-anchored, draggable, TWO-DETENT card
 * over the live map.
 *
 * It appears [RouteSheetDetent.Collapsed] — destination, distance/ETA and Start,
 * nothing else — so the first thing the user sees after picking a place is the
 * WHOLE route on the map rather than a wall of maneuvers. The step-by-step
 * directions are revealed by dragging the handle up (or tapping it), and put
 * away by dragging back down. Previously this sheet always rendered its full
 * step list, took over half the screen and could not be moved.
 *
 * **Start is in the layout above the reveal, not inside it**, so it is on screen
 * in BOTH detents by construction: the sheet grows UPWARDS out of the bottom
 * edge (the revealed list is a variable-height gap in the middle of the card)
 * instead of translating, which also keeps the button clear of the navigation
 * bar in both states. A translating sheet would have had to push its own bottom
 * — and therefore Start — off-screen to collapse.
 *
 * The drag handle, the drag arithmetic and the list-vs-sheet nested-scroll split
 * are the shell panels' ([TranslucentShellPanel] / the chat hub), not a second
 * hand-rolled gesture — see [RouteSheetDrag].
 *
 * @param onCollapsedHeightChanged reports the sheet's COLLAPSED height in px so
 *   the map camera can keep exactly that much of the screen clear when it fits
 *   the route (see [RouteSheetMetrics.cameraBottomPadPx]). Measured rather than
 *   assumed: the peek's height moves with the font scale, the navigation-bar
 *   inset and whether the convoy action is present.
 */
@Composable
private fun RouteSheet(
    state: NavUiState,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onSave: () -> Unit,
    onSetAsConvoyDestination: (() -> Unit)?,
    convoyDestinationEnabled: Boolean,
    onCollapsedHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val destination = state.destination ?: return
    // Filled bookmark once this destination is already saved — so the button
    // doubles as the "is this a favourite?" indicator and its edit affordance.
    val alreadySaved = SavedPlaces.find(state.savedPlaces, destination) != null
    val route = state.route
    // Nothing to reveal without maneuvers to read: no route yet, a routing
    // error, or a degenerate single-point route. The sheet is then peek-only and
    // the handle neither drags nor toggles, rather than opening onto a void.
    val hasSteps = route != null && route.steps.isNotEmpty()

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // How far the sheet can travel: the height the revealed step list gets.
    val rangePx =
        if (hasSteps) {
            with(density) {
                RouteSheetMetrics
                    .stepsRevealHeightDp(configuration.screenHeightDp.toFloat())
                    .dp
                    .toPx()
            }
        } else {
            0f
        }

    // The settled state. rememberSaveABLE so a rotation mid-read keeps the user
    // where they were, KEYED ON THE DESTINATION so searching again while the
    // sheet is open puts the new route's sheet back down at the peek — the
    // "collapsed immediately when the route appears" rule holds for the second
    // route as well as the first. The pixel offset below is always re-derived
    // from this and the freshly measured range, so a config change can never
    // leave the sheet stuck at a stale height.
    var detent by
        rememberSaveable(destination.id) { mutableStateOf(RouteSheetDetent.Collapsed) }
    // Live reveal in px; 0 = collapsed, rangePx = expanded, in between only
    // while a drag or the settle animation is in flight.
    val reveal = remember { Animatable(0f) }
    var sheetHeightPx by remember { mutableIntStateOf(0) }
    var stepsHeightPx by remember { mutableIntStateOf(0) }

    // The live pixel reveal has to be reset per DESTINATION as well as per detent.
    // `detent` gets that for free from rememberSaveable(destination.id) above, but
    // `reveal` is a plain remember whose Animatable survives the swap — so picking
    // a new destination while the sheet was expanded kept the OLD reveal. That
    // reads as a tall EMPTY gap, because NavigationController.select() sets the new
    // destination and `route = null` in the SAME update: there are no maneuvers to
    // draw in the space being held open.
    //
    // snapTo, not animateTo: a new destination's sheet should already BE at the
    // peek the first time it is drawn, not animate down to it.
    //
    // The Animatable INSTANCE is deliberately kept rather than re-remembered on
    // the destination key: the nested-scroll connection below is remembered once
    // and closes over it, so swapping the instance would leave the drag writing
    // into a dead Animatable — the same trap the rememberUpdatedState comment
    // further down exists to avoid.
    LaunchedEffect(destination.id) { reveal.snapTo(0f) }

    // Drive the reveal from the settled detent. Also re-runs when the range
    // changes — a rotation, or the route resolving — so the sheet re-lands
    // exactly on a detent instead of keeping a pixel value that no longer means
    // anything (collapsing to 0 when there is nothing left to show).
    LaunchedEffect(detent, rangePx) {
        reveal.animateTo(RouteSheetDrag.revealForDetent(detent, rangePx))
    }

    // Both heights come from the same layout pass, so the difference is the peek
    // height even mid-animation (the card grows by exactly what the list grows
    // by) and the camera is never told to clear a half-expanded sheet.
    LaunchedEffect(sheetHeightPx, stepsHeightPx) {
        onCollapsedHeightChanged(RouteSheetDrag.collapsedHeightPx(sheetHeightPx, stepsHeightPx))
    }

    // rememberUpdatedState so the nested-scroll connection — captured once —
    // always sees the CURRENT range and settle, rather than the ones that
    // happened to be in scope when it was created. (The detent's backing state is
    // itself replaced whenever the destination changes, so capturing the setter
    // directly would write into a dead state after a re-search.)
    val currentRange by rememberUpdatedState(rangePx)
    val settle: (Float) -> Unit = { velocity ->
        val target = RouteSheetDrag.settleDetent(reveal.value, velocity, currentRange)
        detent = target
        // BOTH the detent write above and this animateTo are required — do NOT
        // "simplify" this to rely on LaunchedEffect(detent, rangePx) alone.
        //
        // The common settle is back to the detent the drag STARTED from: nudge the
        // sheet up 20% of the range, release with no real velocity, and
        // settleDetent returns Collapsed again. Writing an equal value to a
        // MutableState does not invalidate its readers, so the LaunchedEffect key
        // is unchanged and the effect never re-runs — this animateTo is the only
        // thing that returns the sheet to the peek. Without it the sheet would
        // hang wherever the finger left it.
        //
        // When the detent DOES change, the effect also animates to the same
        // target. That is redundant but harmless: both calls drive the same
        // Animatable through its MutatorMutex, so the second re-targets the first
        // from its current value and velocity rather than fighting it.
        scope.launch { reveal.animateTo(RouteSheetDrag.revealForDetent(target, currentRange)) }
    }
    val currentSettle by rememberUpdatedState(settle)

    val nestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                // UNDISPATCHED here and below: these callbacks READ reveal.value
                // synchronously to clamp and WRITE it from a coroutine, so the
                // write must land before the callback returns or the next scroll
                // event in the same frame clamps against a stale reveal and the
                // sheet overshoots its detents. (Same reasoning, and the same
                // fix, as the shell panel's connection.)
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val taken =
                        RouteSheetDrag.preScrollConsumption(
                            availableY = available.y,
                            revealPx = reveal.value,
                            rangePx = currentRange,
                        )
                    if (taken == 0f) return Offset.Zero
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        reveal.snapTo(
                            RouteSheetDrag.clampReveal(reveal.value - taken, currentRange),
                        )
                    }
                    return Offset(0f, taken)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    val taken =
                        RouteSheetDrag.postScrollConsumption(
                            availableY = available.y,
                            revealPx = reveal.value,
                        )
                    if (taken == 0f) return Offset.Zero
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        reveal.snapTo(
                            RouteSheetDrag.clampReveal(reveal.value - taken, currentRange),
                        )
                    }
                    return Offset(0f, taken)
                }

                // A fling that starts while the sheet is off a detent belongs to
                // the SHEET: settle it and swallow the velocity so the step list
                // does not also fling underneath.
                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (reveal.value < currentRange) {
                        currentSettle(available.y)
                        available
                    } else {
                        Velocity.Zero
                    }
            }
        }

    val handleLabel =
        stringResource(
            if (detent == RouteSheetDetent.Expanded) {
                R.string.addressSearch_directionsCollapse
            } else {
                R.string.addressSearch_directionsExpand
            },
        )

    Surface(
        shape = RoundedCornerShape(topStart = KccRadius.xl, topEnd = KccRadius.xl),
        // Shared Aero translucency, like the shell panels and the map popups: the
        // route line and the map keep showing through the sheet's edge, which is
        // what stops a bottom-anchored card reading as a second screen.
        color = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .onSizeChanged { sheetHeightPx = it.height }
                .nestedScroll(nestedScrollConnection)
                .testTag(NAV_ROUTE_SHEET_TEST_TAG),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            PanelDragHandle(
                onDrag = { delta ->
                    // Positive delta is downwards, which CLOSES the reveal.
                    scope.launch {
                        reveal.snapTo(RouteSheetDrag.clampReveal(reveal.value - delta, rangePx))
                    }
                },
                onDragStopped = settle,
                description = handleLabel,
                testTag = NAV_ROUTE_SHEET_HANDLE_TEST_TAG,
                // Tap toggles, so the directions are reachable without a drag.
                onClick =
                    if (hasSteps) {
                        { detent = RouteSheetDrag.toggle(detent) }
                    } else {
                        null
                    },
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = KccSpacing.s4,
                            end = KccSpacing.s4,
                            bottom = KccSpacing.s4,
                        ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
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
                    IconButton(onClick = onSave, modifier = Modifier.testTag(NAV_SAVE_TEST_TAG)) {
                        Icon(
                            imageVector =
                                if (alreadySaved) {
                                    Icons.Filled.Bookmark
                                } else {
                                    Icons.Filled.BookmarkBorder
                                },
                            contentDescription =
                                stringResource(
                                    if (alreadySaved) {
                                        R.string.addressSearch_savedEdit
                                    } else {
                                        R.string.addressSearch_savedAdd
                                    },
                                ),
                            tint =
                                if (alreadySaved) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.addressSearch_clearRoute),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(KccSpacing.s3))

                // The peek's one line of substance: what this route costs. Shown
                // in BOTH detents — collapsing hides the turns, never the answer
                // to "is this trip worth it".
                when {
                    state.routeLoading ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
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

                    route != null -> RouteSummaryLine(route = route)
                }

                // The revealed part. Its height is read in the LAYOUT phase (not
                // composition), so the expand/collapse animation re-lays-out
                // without recomposing the whole sheet 60 times a second.
                //
                // Modifier ORDER matters twice here: `onSizeChanged` sits OUTSIDE
                // `layout` so it reports the height this area actually occupies in
                // the card (the reveal) rather than the height the list would have
                // liked; and `clipToBounds` sits outside it too, so a list measured
                // taller than the current reveal is cut off at the reveal instead
                // of spilling over the Start button below.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { stepsHeightPx = it.height }
                            .clipToBounds()
                            .layout { measurable, constraints ->
                                val revealed = reveal.value.roundToInt().coerceAtLeast(0)
                                val placeable =
                                    measurable.measure(
                                        constraints.copy(minHeight = 0, maxHeight = revealed),
                                    )
                                layout(placeable.width, revealed) { placeable.place(0, 0) }
                            }
                            .testTag(NAV_ROUTE_STEPS_TEST_TAG),
                ) {
                    if (route != null) RouteSteps(route = route)
                }

                Spacer(modifier = Modifier.height(KccSpacing.s3))

                // Prominent "Start" CTA once a route is resolved — begins
                // turn-by-turn navigation (Google-Maps style). Always shown with a
                // usable route, and in BOTH detents: the HOST decides HOW to
                // navigate (see onStartNavigation) — the in-app Mapbox
                // turn-by-turn screen when the Navigation SDK is bundled
                // (BuildConfig.NAV_SDK_ENABLED), else a handoff to the device's
                // maps app. Either way the button is reachable in every build and
                // every sheet state, so the route preview never dead-ends without
                // a way to start driving.
                if (route != null) {
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

                // "Set for the convoy" — only present when the host opened this
                // screen as the convoy bar's place picker. Shown as soon as a
                // DESTINATION is resolved, not only once a ROUTE is: sharing a place
                // with the group is meaningful even if this phone can't route to it
                // right now (no GPS fix, directions request failed). Disabled while
                // `convoy-setDestination` does not exist.
                if (onSetAsConvoyDestination != null) {
                    Spacer(modifier = Modifier.height(KccSpacing.s2))
                    OutlinedButton(
                        onClick = onSetAsConvoyDestination,
                        enabled = convoyDestinationEnabled,
                        modifier = Modifier.fillMaxWidth().testTag(NAV_CONVOY_DESTINATION_TEST_TAG),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Group,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text =
                                stringResource(
                                    if (convoyDestinationEnabled) {
                                        R.string.convoy_barDestinationPickAction
                                    } else {
                                        R.string.convoy_barDestinationSetUnavailable
                                    },
                                ),
                            modifier = Modifier.padding(start = KccSpacing.s2),
                        )
                    }
                }
            }
        }
    }
}

/** Distance · ETA — the peek's headline, present in both detents. */
@Composable
private fun RouteSummaryLine(route: RouteSummary) {
    val unitM = stringResource(R.string.addressSearch_unitMeters)
    val unitKm = stringResource(R.string.addressSearch_unitKilometers)
    val unitMin = stringResource(R.string.addressSearch_unitMinutes)
    val unitH = stringResource(R.string.addressSearch_unitHours)

    Text(
        // Distance first, then duration/ETA — matching the app convention
        // (e.g. DrivesScreen): "4.5 km · 12 min".
        text =
            stringResource(
                R.string.addressSearch_routeSummary,
                NavFormat.formatDistance(route.distanceMeters, unitM, unitKm),
                NavFormat.formatDuration(route.durationSeconds, unitMin, unitH),
            ),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The step-by-step maneuver list — the part the sheet reveals.
 *
 * Fills whatever height the reveal currently gives it and scrolls inside that.
 * The sheet's nested-scroll connection sits above this, so scrolling mid-list
 * scrolls the list and only a downward drag with the list already at its top
 * collapses the sheet.
 */
@Composable
private fun RouteSteps(route: RouteSummary) {
    val unitM = stringResource(R.string.addressSearch_unitMeters)
    val unitKm = stringResource(R.string.addressSearch_unitKilometers)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.addressSearch_directionsTitle),
            modifier = Modifier.padding(top = KccSpacing.s3, bottom = KccSpacing.s2),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
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
