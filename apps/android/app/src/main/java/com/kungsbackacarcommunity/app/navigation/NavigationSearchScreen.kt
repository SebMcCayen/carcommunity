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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
 *   long-press "navigate here"): it is reverse-geocoded for a label and fed into
 *   the same route preview + Start flow as a searched place. Null for a normal
 *   search-first open.
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

    // Pick a place (suggestion or recent) AND dismiss the keyboard first, so the
    // route sheet's directions aren't left behind the IME (see above).
    val onSelectPlace: (PlaceSuggestion) -> Unit = { place ->
        keyboardController?.hide()
        focusManager.clearFocus()
        controller.select(place)
    }

    // Fetch the origin up-front so the first suggestions are location-biased.
    LaunchedEffect(controller) { controller.refreshOrigin() }

    // A map long-press "navigate here": immediately preview + route to the pressed
    // coordinate (reverse-geocoded for its label) through the same flow as a
    // searched place. Keyed on the target so a new long-press re-previews.
    val droppedPinLabel = stringResource(R.string.addressSearch_droppedPin)
    LaunchedEffect(controller, initialTarget) {
        if (initialTarget != null) controller.selectPoint(initialTarget, droppedPinLabel)
    }

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
            onDismiss = { saveTarget = null },
            onSave = { kind, label ->
                controller.savePlace(target, kind, label)
                saveTarget = null
            },
            onRemove = { id ->
                controller.removeSavedPlace(id)
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
 * Localized display name for a saved place: the singletons always read "Home"/
 * "Work" from resources (the user's stored label is irrelevant there and would
 * be the raw street name for a Home saved straight from a search result),
 * favourites read the user's own label.
 */
@Composable
private fun SavedPlace.displayLabel(): String =
    when (kind) {
        SavedPlaceKind.Home -> stringResource(R.string.addressSearch_savedHome)
        SavedPlaceKind.Work -> stringResource(R.string.addressSearch_savedWork)
        SavedPlaceKind.Favourite -> label
    }

private fun SavedPlaceKind.icon() =
    when (this) {
        SavedPlaceKind.Home -> Icons.Filled.Home
        SavedPlaceKind.Work -> Icons.Filled.Work
        SavedPlaceKind.Favourite -> Icons.Filled.Star
    }

/**
 * The user's saved places in the empty search state. Tapping a row selects it as
 * the destination — the identical [NavigationController.select] path a search
 * result takes — so a saved place is genuinely one tap from a route preview. The
 * trailing edit button opens the same dialog the route sheet's save button does,
 * pre-filled, for rename / re-kind / remove.
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
            saved.take(SavedPlaces.SHOWN).forEachIndexed { index, place ->
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
    onDismiss: () -> Unit,
    onSave: (SavedPlaceKind, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var kind by remember(existing) { mutableStateOf(existing?.kind ?: SavedPlaceKind.Favourite) }
    var label by
        remember(existing) { mutableStateOf(existing?.label ?: place.name) }

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
            recents.take(RecentSearches.SHOWN).forEachIndexed { index, place ->
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

@Composable
private fun RouteSheet(
    state: NavUiState,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val destination = state.destination ?: return
    // Filled bookmark once this destination is already saved — so the button
    // doubles as the "is this a favourite?" indicator and its edit affordance.
    val alreadySaved = SavedPlaces.find(state.savedPlaces, destination) != null
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
                IconButton(onClick = onSave, modifier = Modifier.testTag(NAV_SAVE_TEST_TAG)) {
                    Icon(
                        imageVector =
                            if (alreadySaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
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

            // Prominent "Start" CTA once a route is resolved — begins turn-by-turn
            // navigation (Google-Maps style). Always shown with a usable route: the
            // HOST decides HOW to navigate (see onStartNavigation) — the in-app
            // Mapbox turn-by-turn screen when the Navigation SDK is bundled
            // (BuildConfig.NAV_SDK_ENABLED), else a handoff to the device's maps
            // app. Either way the button is reachable in every build, so the
            // route preview never dead-ends without a way to start driving.
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
