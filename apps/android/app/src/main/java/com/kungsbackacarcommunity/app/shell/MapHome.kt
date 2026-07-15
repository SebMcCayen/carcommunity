package com.kungsbackacarcommunity.app.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.LocalKccStatusColors
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.incidents.IncidentTypePickerDialog
import com.kungsbackacarcommunity.app.live.LiveDurationPicker
import com.kungsbackacarcommunity.app.live.LiveSessionDuration

/** Test tag on the whole map-first home, so UI tests can assert it renders. */
const val MAP_HOME_TEST_TAG = "map_home"

/**
 * Shared surface opacity for the map-overlay popups (chat, layers, and
 * live-location). Slightly translucent so the live map shows through a little
 * and all the popups read as one consistent floating layer, while staying
 * opaque enough to be readable.
 */
private const val POPUP_SURFACE_ALPHA = 0.92f

/**
 * The map-first home (Waze/Life360 style): a full-bleed [MapSurface] behind a
 * prominent "Where to?" search bar, a transient "Loading roads…" status line,
 * and a right-side stack of floating circular controls. The user's own position
 * is drawn by the surface itself (the Mapbox location puck at the real GPS
 * location), so it stays anchored to the ground when the map pans rather than
 * sliding with the screen centre.
 *
 * All map interaction is routed through [mapSurface]; the real Mapbox render +
 * GPS puck sit behind that same seam (the stub renders a neutral placeholder).
 * The caller's marker model is still pushed via [MapSurface.setUserMarker] so
 * the surface can reflect live-sharing state on the puck.
 *
 * @param isLiveSharing whether the live-location session is currently sharing —
 *   turns the broadcast control GREEN and is pushed to the surface so the puck
 *   can signal live sharing (wired to the real live-location state). Tapping the
 *   broadcast control opens a transparent [Popup] *over* the map (no dimming
 *   scrim, same idiom as the layers popup) presenting the live-location options
 *   rather than toggling sharing directly.
 * @param canShareLive whether the caller may START a session (live-location flag
 *   on AND active member); mirrors the backend member check. When false the
 *   popup shows the membership teaser instead of the duration/start controls.
 *   Stop / Hide-me-now are governed by [isLiveSharing] (not this flag), so they
 *   appear only while a session is already active; the details entry point
 *   stays reachable regardless.
 * @param onStartLiveShare start a session for the chosen [LiveSessionDuration]
 *   (wired to LiveLocationCoordinator.start); only offered when [canShareLive].
 * @param onStopLiveShare stop the active session (wired to
 *   LiveLocationCoordinator.stop); offered while sharing.
 * @param onHideMeNow privacy stop — remove my position now (wired to
 *   LiveLocationCoordinator.hideMeNow); offered while sharing.
 * @param onOpenLiveShareDetails open the full LiveLocationScreen for the
 *   complete controls + privacy details.
 * @param participantCount other members stashed to show on the map (e.g. a
 *   group-drive roster); surfaced as a small chip, preserved for the real impl.
 * @param avatarUrl resolved download URL for the signed-in user's profile
 *   picture, shown in the top-right profile button; null falls back to the
 *   generic account icon.
 * @param moreMenuEntries the profile/account menu items (Profile, Friends,
 *   Settings, Sign out, …). Tapping the top-right profile button opens these in
 *   a transparent [Popup] *over* the map (no dimming scrim, so the map stays
 *   visible) rather than navigating to a full-screen hub. Unavailable entries
 *   (null [HubEntry.onClick]) are omitted; tapping an available entry runs its
 *   action (which navigates to that destination, or signs out) and closes the
 *   popup, and tapping outside the popup dismisses it.
 * @param unreadChatCount unread indicator for the floating chat control: a
 *   non-zero value renders the "missed" badge/dot on the bubble. It is sourced
 *   from the community-chat unread state (the hoisted `observeUnread` marker
 *   collected once in `AuthenticatedApp`), so the dot shows whenever the caller
 *   has an unread community message.
 * @param onOpenChat invoked when the floating chat bubble is tapped; the host
 *   opens the full 3-channel chat hub ([ShellRoute.ChatHub]).
 */
@Composable
fun MapHome(
    mapSurface: MapSurface,
    isLiveSharing: Boolean,
    canShareLive: Boolean,
    participantCount: Int,
    userLabel: String,
    avatarUrl: String? = null,
    onSearch: () -> Unit,
    onStartLiveShare: (LiveSessionDuration) -> Unit,
    onStopLiveShare: () -> Unit,
    onHideMeNow: () -> Unit,
    onOpenLiveShareDetails: () -> Unit,
    onRecenter: () -> Unit,
    moreMenuEntries: List<HubEntry>,
    modifier: Modifier = Modifier,
    unreadChatCount: Int = 0,
    // Tapping the floating chat bubble opens the full 3-channel chat hub
    // (Community / Convoys / Friends + Notifications) as a full-screen route,
    // rather than the old inline placeholder popup. Defaults to a no-op so
    // existing callers/tests that don't wire chat still compile.
    onOpenChat: () -> Unit = {},
    // Crowd-sourced + Trafikverket incidents layer. [incidentMarkers] are drawn
    // on the map so every user sees them, BUT only while [incidentsLayerEnabled]
    // (the "Traffic alerts" toggle in the layers popup) is on — flipping it off
    // pushes an empty set to the surface, and [onIncidentsLayerEnabledChange]
    // reports the flip to the host so it can (re)fetch nearby incidents when the
    // layer is switched back on. The report control (opening the type picker) is
    // shown only when [incidentReportingEnabled] (a repository is configured),
    // and a pick invokes [onReportIncident]. All default so existing
    // callers/tests are unaffected (layer on, no reporting).
    incidentMarkers: List<MapIncidentMarker> = emptyList(),
    incidentsLayerEnabled: Boolean = true,
    onIncidentsLayerEnabledChange: (Boolean) -> Unit = {},
    incidentReportingEnabled: Boolean = false,
    onReportIncident: (IncidentType) -> Unit = {},
) {
    val loadState by mapSurface.loadState.collectAsState()
    val trafficOn by mapSurface.trafficEnabled.collectAsState()
    val is3d by mapSurface.is3d.collectAsState()
    val bearing by mapSurface.bearing.collectAsState()

    // Keep the surface's marker in sync with the live-share state + display name.
    // Keyed on mapSurface too so the marker is re-pushed if the surface instance
    // is swapped (e.g. StubMapSurface -> a real Mapbox-backed surface).
    LaunchedEffect(mapSurface, userLabel, isLiveSharing) {
        mapSurface.setUserMarker(MapUserMarker(label = userLabel, isLiveSharing = isLiveSharing))
    }

    // Day/night follows the Android system Dark theme by default: on open the map
    // matches the device theme, and it live-updates if the system flips while the
    // app is open (e.g. Android's scheduled sunset->sunrise dark theme). Once the
    // user flips day/night manually in the layers popup, [desiredMapMode] holds
    // their explicit choice (non-null) and this effect applies it instead of the
    // system theme for the rest of the session. While [desiredMapMode] is null the
    // map follows the system default ([systemDefaultMode]).
    // Keyed on mapSurface too (mirrors the setUserMarker effect above) so the
    // *effective* mode is re-applied if the surface instance is swapped (e.g.
    // StubMapSurface -> a real Mapbox-backed surface); a fresh surface starts at
    // its default MapMode, so without this key it would keep that default —
    // dropping the user's manual override — until the system theme flipped or the
    // user toggled manually again. Applying the effective mode here means the
    // manual choice survives a surface swap.
    // Persisted with [rememberSaveable] (not plain [remember]) so the user's manual
    // day/night override survives configuration changes / activity recreation (e.g.
    // rotation). With plain remember it would reset to null on rotation and the
    // effect below would immediately snap the map back to [systemDefaultMode],
    // dropping the manual choice. MapMode is a simple enum, so a name-based Saver
    // stores the nullable value.
    var desiredMapMode by rememberSaveable(
        stateSaver = Saver(
            save = { it?.name },
            // SAFE parse: MapMode.valueOf(...) THROWS on an unknown constant name —
            // e.g. after an app update that renames/removes an enum value, or
            // corrupted saved state — which would crash activity recreation.
            // entries.find returns null for an unknown name (falls back to
            // "follow system") instead of throwing.
            restore = { saved -> (saved as? String)?.let { name -> MapMode.entries.find { it.name == name } } },
        ),
    ) { mutableStateOf<MapMode?>(null) }
    val systemInDark = isSystemInDarkTheme()
    val systemDefaultMode = if (systemInDark) MapMode.Night else MapMode.Day
    LaunchedEffect(mapSurface, systemInDark, desiredMapMode) {
        mapSurface.setMapMode(desiredMapMode ?: systemDefaultMode)
    }

    // Push the incident markers onto the surface whenever they change (or the
    // surface instance is swapped), so every user sees them — but ONLY while the
    // "Traffic alerts" layer is enabled. When it is off we push an empty set so
    // the surface clears the markers (and the Trafikverket attribution hides),
    // giving the toggle a real on/off effect on the map.
    LaunchedEffect(mapSurface, incidentMarkers, incidentsLayerEnabled) {
        mapSurface.setIncidentMarkers(if (incidentsLayerEnabled) incidentMarkers else emptyList())
    }

    // Incident-report type picker open/close is local UI state: tapping the
    // report control opens it, picking a type reports and closes it.
    var reportOpen by remember { mutableStateOf(false) }

    // Profile/account menu open/close is local UI state too: tapping the
    // top-right profile button opens the menu as a transparent Popup *over* the
    // map (so the map stays visible), tapping outside it dismisses.
    var moreOpen by remember { mutableStateOf(false) }

    // Map-layers popup open/close is local UI state: tapping the layers control
    // opens the transparent toggle sheet, tapping outside it dismisses it.
    var layersOpen by remember { mutableStateOf(false) }

    // Search bar collapsed/expanded is local UI state: it starts collapsed to a
    // round search-icon button in the upper-left; tapping it expands the
    // full-width "Where to?" bar, and tapping outside (the transparent scrim
    // below) collapses it back to the icon.
    var searchExpanded by remember { mutableStateOf(false) }

    // Accessible dismiss for the expanded search: the outside-tap scrim is
    // deliberately invisible to TalkBack/keyboard, so system Back is the
    // reliable way to collapse the bar (only intercepts Back while expanded).
    BackHandler(enabled = searchExpanded) { searchExpanded = false }

    // Live-location popup open/close is local UI state: tapping the broadcast
    // control opens the transparent live-share sheet (over the map, no scrim),
    // tapping outside it dismisses it.
    var liveOpen by remember { mutableStateOf(false) }

    // Test hook only — a testTag (not contentDescription) so the internal tag
    // string never leaks into TalkBack. The container itself is decorative.
    Box(modifier = modifier.fillMaxSize().testTag(MAP_HOME_TEST_TAG)) {
        // Full-bleed map (behind everything). The user's own position is drawn
        // by the surface itself (the Mapbox location puck at the real GPS
        // location), so it stays put when the map pans — there is deliberately
        // no centre-locked Compose "You" overlay here.
        mapSurface.Content(Modifier.fillMaxSize())

        // Transparent outside-tap catcher, shown only while the search bar is
        // expanded: a tap on the open map area collapses it back to the round
        // icon. It's composed here, above the map but below every later overlay
        // (the search row and the right-side floating controls composed next),
        // so it only catches taps that fall through to the map. Taps on the bar
        // itself, or on the floating controls, are consumed by those overlays
        // (composed above the scrim) and don't collapse via this layer — the
        // controls just do their own thing. A raw pointerInput tap handler (no
        // ripple, no clickable role) plus clearAndSetSemantics keeps this
        // invisible dismiss layer out of the accessibility tree — otherwise it
        // exposes an unlabeled focusable "Button" node over the whole map to
        // TalkBack.
        if (searchExpanded) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { searchExpanded = false }
                        }
                        .clearAndSetSemantics {},
            )
        }

        // Top: search bar + avatar/menu, then the loading status line.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBarRow(
                avatarUrl = avatarUrl,
                onSearch = onSearch,
                onOpenMore = { moreOpen = true },
                searchExpanded = searchExpanded,
                onExpandSearch = { searchExpanded = true },
                // The profile/account menu popup is composed next to the profile
                // button (inside SearchBarRow) so the Popup anchors to the
                // button's real measured bounds instead of a hard-coded offset.
                moreMenuEntries = moreMenuEntries,
                moreMenuOpen = moreOpen,
                onDismissMore = { moreOpen = false },
            )
            if (loadState == MapLoadState.Loading) {
                LoadingRoadsChip()
            }
            if (participantCount > 0) {
                ParticipantChip(count = participantCount)
            }
        }

        // Right-side floating controls, bottom-right.
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val statusColors = LocalKccStatusColors.current
            // 0. Compass — a north-arrow rotated by the current map bearing so it
            //    keeps pointing at true north as the map rotates; tapping it eases
            //    the map back to north-up. Sits at the top of the stack, above the
            //    live-location control. The built-in Mapbox compass is disabled in
            //    MapboxMapSurface so this is the only compass shown.
            CircleControl(
                icon = Icons.Filled.Navigation,
                contentDescription = stringResource(R.string.shell_compass),
                onClick = { mapSurface.resetNorth() },
                iconRotationDegrees = -bearing,
                modifier = Modifier.testTag(MAP_HOME_COMPASS_TAG),
            )
            // 1. Live-location broadcast control — GREEN when sharing. Opens the
            //    transparent live-share popup (over the map, no scrim) with the
            //    session options rather than toggling sharing directly.
            CircleControl(
                icon = Icons.Filled.Podcasts,
                contentDescription =
                    stringResource(
                        if (isLiveSharing) R.string.shell_liveShareOn else R.string.shell_liveShareOff,
                    ),
                containerColor =
                    if (isLiveSharing) statusColors.success else MaterialTheme.colorScheme.surface,
                contentColor =
                    if (isLiveSharing) Color.White else MaterialTheme.colorScheme.onSurface,
                onClick = { liveOpen = true },
                modifier = Modifier.testTag(MAP_HOME_LIVE_TAG),
            )
            // 1b. Report-incident control — opens the type picker. Shown only
            //     when incident reporting is available (a repository configured).
            if (incidentReportingEnabled) {
                CircleControl(
                    icon = Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.incidents_reportButton),
                    containerColor = statusColors.warning,
                    contentColor = Color.White,
                    onClick = { reportOpen = true },
                    modifier = Modifier.testTag(MAP_HOME_REPORT_TAG),
                )
            }
            // 2. Map-layers control — opens the transparent layers popup
            //    (traffic / day-night / 3D toggles). Highlighted while any of
            //    those non-default layers is active, so an enabled overlay is
            //    still discoverable from the collapsed control. Day/night counts
            //    as "active" only when the user has manually DEVIATED from the
            //    system default (desiredMapMode set AND different from the theme
            //    default) — a system-driven default Night (dark theme, untouched)
            //    must NOT light up the button.
            val layersActive =
                trafficOn ||
                    (desiredMapMode != null && desiredMapMode != systemDefaultMode) ||
                    !is3d
            CircleControl(
                icon = Icons.Filled.Layers,
                contentDescription = stringResource(R.string.shell_layersButton),
                containerColor =
                    if (layersActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                contentColor =
                    if (layersActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                onClick = { layersOpen = true },
                modifier = Modifier.testTag(MAP_HOME_LAYERS_TAG),
            )
            // 3. Recenter / my-location — calls MapSurface.recenter().
            CircleControl(
                icon = Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.shell_recenter),
                onClick = onRecenter,
            )
            // 4. Chat bubble — opens the 3-channel chat hub; shows a badge with
            //    the unread ("missed") message count when > 0.
            ChatCircleControl(
                unreadCount = unreadChatCount,
                onClick = onOpenChat,
            )
        }

        // Incident-report type picker. Picking a type reports it (the host
        // resolves the current location) and closes the dialog.
        if (reportOpen) {
            IncidentTypePickerDialog(
                onPick = { type ->
                    reportOpen = false
                    onReportIncident(type)
                },
                onDismiss = { reportOpen = false },
            )
        }

        // The profile/account menu popup itself lives inside SearchBarRow,
        // anchored to the profile button's measured bounds (see ProfileMenuPopup).

        // Map-layers overlay: a transparent popup (no scrim) so the map stays
        // visible behind it while the user flips the traffic / day-night / 3D
        // toggles. Each toggle reads and writes the surface state live.
        if (layersOpen) {
            MapLayersPopup(
                incidentsOn = incidentsLayerEnabled,
                onIncidentsChange = onIncidentsLayerEnabledChange,
                trafficOn = trafficOn,
                // Drive the night Switch off the IMMEDIATE user intent (the
                // effective desired mode) rather than the SURFACE's mapMode, which
                // only updates after the LaunchedEffect runs and the surface flow
                // re-emits. onNightModeChange sets [desiredMapMode] synchronously,
                // so the Switch flips at once instead of snapping back / jittering
                // while it waits for the surface to catch up.
                nightMode = (desiredMapMode ?: systemDefaultMode) == MapMode.Night,
                is3d = is3d,
                onTrafficChange = { mapSurface.setTrafficEnabled(it) },
                onNightModeChange = {
                    // A manual flip records the user's explicit choice in
                    // [desiredMapMode]; the system-follow effect above then applies
                    // this mode (instead of the theme default) for the rest of the
                    // session — and re-applies it if the surface instance is swapped.
                    val mode = if (it) MapMode.Night else MapMode.Day
                    desiredMapMode = mode
                    // Apply immediately so the map restyles on the same frame the
                    // user toggles, instead of waiting for the system-follow effect
                    // coroutine to re-run; that effect stays idempotent and simply
                    // re-applies this same mode when it next runs.
                    mapSurface.setMapMode(mode)
                },
                on3dChange = { mapSurface.set3dEnabled(it) },
                onDismiss = { layersOpen = false },
            )
        }

        // Live-location overlay: a transparent popup (no scrim) so the map stays
        // visible behind it while the user starts/stops sharing, hides now, or
        // opens the full live-location screen. Reflects the current sharing state
        // and honours the member gate on starting (canShareLive).
        if (liveOpen) {
            LiveSharePopup(
                isSharing = isLiveSharing,
                canShareLive = canShareLive,
                onStart = onStartLiveShare,
                onStop = onStopLiveShare,
                onHideMeNow = onHideMeNow,
                onOpenDetails = onOpenLiveShareDetails,
                onDismiss = { liveOpen = false },
            )
        }
    }
}

/** Test tag on the floating map-layers control. */
const val MAP_HOME_LAYERS_TAG = "map_home_layers"

/** Test tag on the floating compass control (top of the right-side stack). */
const val MAP_HOME_COMPASS_TAG = "map_home_compass"

/** Test tag on the collapsed round search button (upper-left). */
const val MAP_HOME_SEARCH_TAG = "map_home_search"

/** Test tag on the floating report-incident control. */
const val MAP_HOME_REPORT_TAG = "map_home_report"

/** Test tag on the map-layers popup card. */
const val MAP_HOME_LAYERS_POPUP_TAG = "map_home_layers_popup"

/** Test tag on the incidents ("Traffic alerts") layer toggle switch. */
const val MAP_HOME_LAYERS_INCIDENTS_TAG = "map_home_layers_incidents"

/** Test tag on the floating live-location broadcast control. */
const val MAP_HOME_LIVE_TAG = "map_home_live"

/** Test tag on the live-location popup card. */
const val MAP_HOME_LIVE_POPUP_TAG = "map_home_live_popup"

/**
 * The transparent map-layers popup shown when the layers control is tapped.
 * Rendered as a [Popup] (not a [Dialog]) so there is NO dimming scrim and the
 * live map stays fully visible behind the toggle card; tapping outside the card
 * or pressing Back dismisses it (focusable popup). Each row reflects the current
 * surface state and updates it live:
 * - Traffic alerts — the Trafikverket + crowd-sourced incidents layer (accidents,
 *   roadwork, hazards, police, closures) drawn as coloured markers. This is the
 *   row wired to the Trafikverket open-data attribution below; the host draws /
 *   clears the markers as it flips ([onIncidentsChange]).
 * - Traffic — the Mapbox congestion overlay ([MapSurface.setTrafficEnabled]);
 *   a DIFFERENT data source from the Trafikverket alerts above.
 * - Night mode — the Standard style's day/night light preset
 *   ([MapSurface.setMapMode]).
 * - 3D buildings — the tilted 3D vs flat 2D camera ([MapSurface.set3dEnabled]).
 *
 * The visible effects (incident markers, congestion lines, light preset, tilt)
 * only render on the real token-provisioned Mapbox surface; on the stub the
 * switches still move so the wiring is exercised without a device.
 */
@Composable
private fun MapLayersPopup(
    incidentsOn: Boolean,
    onIncidentsChange: (Boolean) -> Unit,
    trafficOn: Boolean,
    nightMode: Boolean,
    is3d: Boolean,
    onTrafficChange: (Boolean) -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    on3dChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(16.dp)
                    // Fill available width, then cap at 360.dp: full-width on
                    // phones, capped on tablets (matches LiveSharePopup order).
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .testTag(MAP_HOME_LAYERS_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            // Slightly translucent so the map shows through (matches the chat popup).
            color = MaterialTheme.colorScheme.surface.copy(alpha = POPUP_SURFACE_ALPHA),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.shell_layersTitle),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.shell_layersClose),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Trafikverket + crowd-sourced incidents layer. Listed first as the
                // flagship road-info layer; the "Källa: Trafikverket" attribution
                // below belongs to THIS row and shows only while it is on.
                LayerToggleRow(
                    label = stringResource(R.string.shell_layersIncidents),
                    checked = incidentsOn,
                    onCheckedChange = onIncidentsChange,
                    switchTestTag = MAP_HOME_LAYERS_INCIDENTS_TAG,
                )
                LayerToggleRow(
                    label = stringResource(R.string.shell_layersTraffic),
                    checked = trafficOn,
                    onCheckedChange = onTrafficChange,
                )
                LayerToggleRow(
                    label = stringResource(R.string.shell_layersNightMode),
                    checked = nightMode,
                    onCheckedChange = onNightModeChange,
                )
                LayerToggleRow(
                    label = stringResource(R.string.shell_layers3d),
                    checked = is3d,
                    onCheckedChange = on3dChange,
                )
                // Attribution for the Trafikverket-sourced incidents drawn on the
                // map layer (product-owner requirement: credit Trafikverket wherever
                // we show their open data). Shown only while the incidents layer is
                // on — with it off, no Trafikverket data is on screen to attribute.
                if (incidentsOn) {
                    Text(
                        text = stringResource(R.string.incidents_sourceTrafikverket),
                        modifier = Modifier.padding(top = KccSpacing.s2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A single labelled toggle row in the map-layers popup. */
@Composable
private fun LayerToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier,
        )
    }
}

/**
 * The transparent live-location popup shown when the broadcast control is tapped.
 * Rendered as a [Popup] (not a [Dialog]) so there is NO dimming scrim and the
 * live map stays fully visible behind the sheet — the same idiom as the
 * map-layers popup; tapping outside the card or pressing Back dismisses it
 * (focusable popup). The content reflects the current sharing state and reuses
 * the existing live-location logic (wired through to LiveLocationCoordinator by
 * the caller):
 * - While sharing: a Stop-sharing action and the never-gated Hide-me-now.
 * - Not sharing and [canShareLive]: a session-duration picker + Start.
 * - Not sharing and not [canShareLive]: the membership teaser (starting is
 *   member-gated on the backend), Stop/Hide stay reachable in the sharing state.
 * A "More options" row always opens the full [com.kungsbackacarcommunity.app.live.LiveLocationScreen]
 * for the complete controls + privacy details. Each action closes the popup.
 */
@Composable
private fun LiveSharePopup(
    isSharing: Boolean,
    canShareLive: Boolean,
    onStart: (LiveSessionDuration) -> Unit,
    onStop: () -> Unit,
    onHideMeNow: () -> Unit,
    onOpenDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val statusColors = LocalKccStatusColors.current
    // Pending duration selection, mirroring LiveLocationScreen's picker; only
    // read when starting a fresh session (canShareLive && !isSharing).
    var selectedDuration by remember { mutableStateOf(LiveSessionDuration.ONE_HOUR) }
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(16.dp)
                    // Fill available width, then cap at 360.dp: keeps the sheet
                    // width stable across the member-gated (narrow content) and
                    // normal states instead of shrinking to content width.
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .testTag(MAP_HOME_LIVE_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = POPUP_SURFACE_ALPHA),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Podcasts,
                        contentDescription = null,
                        tint =
                            if (isSharing) statusColors.success else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.shell_liveTitle),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.shell_liveClose),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text =
                        stringResource(
                            if (isSharing) {
                                R.string.liveLocation_statusSharing
                            } else {
                                R.string.liveLocation_statusNotSharing
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                when {
                    isSharing -> {
                        // Stopping is authenticated-gated (not member-gated) on the
                        // backend, so it is always offered while a session is active.
                        Button(
                            onClick = {
                                onStop()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.liveLocation_stop))
                        }
                        OutlinedButton(
                            onClick = {
                                onHideMeNow()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.liveLocation_hideNow))
                        }
                    }
                    canShareLive -> {
                        Text(
                            text = stringResource(R.string.liveLocation_durationLabel),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Shared with LiveLocationScreen so the duration options
                        // never drift; the popup has no busy state, so enabled.
                        LiveDurationPicker(
                            selected = selectedDuration,
                            enabled = true,
                            onSelect = { selectedDuration = it },
                        )
                        Button(
                            onClick = {
                                onStart(selectedDuration)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.liveLocation_start))
                        }
                    }
                    else -> {
                        // Starting is member-gated (backend parity) — show the
                        // membership teaser instead of the start controls.
                        Text(
                            text = stringResource(R.string.liveLocation_memberRequiredToShare),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Always offer the full live-location screen for the complete
                // controls + privacy details.
                TextButton(
                    onClick = {
                        onOpenDetails()
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(text = stringResource(R.string.shell_liveDetails))
                }
            }
        }
    }
}

/** Test tag on the profile/account menu popup card. */
const val MAP_HOME_MORE_POPUP_TAG = "map_home_more_popup"

/**
 * The profile/account menu shown when the top-right profile button is tapped.
 * Rendered as a [Popup] (not a [Dialog]) so there is no dimming scrim — the map
 * stays fully visible behind the menu, which is the whole point of overlaying it
 * rather than navigating to a full-screen hub. `focusable = true` lets an
 * outside tap (or the Back gesture) dismiss it via [onDismiss].
 *
 * Must be composed next to the profile button (its direct layout parent is the
 * anchor): the [PopupPositionProvider] then receives the button's real measured
 * bounds and drops the card just below its bottom edge, end-aligned — so the
 * position holds up under font scaling, status-bar/display-cutout insets, and
 * search-row layout changes instead of relying on a hard-coded offset.
 * `anchorBounds` is already in window coordinates, so no extra insets padding
 * is applied here (that would double-count the status bar).
 *
 * Reuses the shared [HubRow] so each row matches the hub landings; unavailable
 * entries (null [HubEntry.onClick]) are omitted. Tapping an entry runs its
 * action — navigating to that destination's full route, or signing out — and
 * then closes the popup.
 */
@Composable
private fun ProfileMenuPopup(
    entries: List<HubEntry>,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider =
        remember(density) {
            val gapPx = with(density) { 8.dp.roundToPx() }
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // End-align the card to the button: the card's trailing edge
                    // sits on the button's trailing edge so the card grows toward
                    // the screen center. anchorBounds is in PHYSICAL window
                    // coordinates (left < right in both directions). LTR: trailing
                    // = physical right → card.right = anchor.right. RTL: the
                    // button sits at the physical LEFT (mirrored Row) and trailing
                    // = physical left → card.left = anchor.left. Clamped so the
                    // card never leaves the window on narrow screens.
                    val x =
                        when (layoutDirection) {
                            LayoutDirection.Ltr -> anchorBounds.right - popupContentSize.width
                            LayoutDirection.Rtl -> anchorBounds.left
                        }.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
                    val y =
                        (anchorBounds.bottom + gapPx)
                            .coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
                    return IntOffset(x, y)
                }
            }
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            // Cap (rather than fix) the width so the card can shrink on narrow
            // windows; the Popup itself constrains content to the window size.
            modifier = Modifier.widthIn(max = 280.dp).testTag(MAP_HOME_MORE_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entries.forEach { entry ->
                    val onClick = entry.onClick
                    if (onClick != null) {
                        HubRow(entry.label, entry.icon) {
                            onClick()
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/** Test tag on the top-right profile/account menu button. */
const val MAP_HOME_MORE_TAG = "map_home_more"

/** Test tag on the floating chat control (bubble). */
const val MAP_HOME_CHAT_TAG = "map_home_chat"

/**
 * A [CircleControl]-styled chat bubble with an unread-count badge. Matches the
 * other floating controls (size/shape/elevation/haptics); the badge is hidden
 * when [unreadCount] is 0.
 */
@Composable
private fun ChatCircleControl(
    unreadCount: Int,
    onClick: () -> Unit,
) {
    val description =
        if (unreadCount > 0) {
            stringResource(R.string.shell_chatUnread, unreadCount)
        } else {
            stringResource(R.string.shell_chat)
        }
    BadgedBox(
        badge = {
            if (unreadCount > 0) {
                Badge {
                    Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                }
            }
        },
        // Merge the descendant CircleControl's click/label semantics into this
        // tagged node so onNodeWithTag(MAP_HOME_CHAT_TAG) exposes the click action
        // (the clickable Surface is a child of the BadgedBox).
        modifier = Modifier.testTag(MAP_HOME_CHAT_TAG).semantics(mergeDescendants = true) {},
    ) {
        CircleControl(
            icon = Icons.AutoMirrored.Filled.Chat,
            contentDescription = description,
            onClick = onClick,
        )
    }
}

@Composable
private fun SearchBarRow(
    avatarUrl: String?,
    onSearch: () -> Unit,
    onOpenMore: () -> Unit,
    searchExpanded: Boolean,
    onExpandSearch: () -> Unit,
    moreMenuEntries: List<HubEntry>,
    moreMenuOpen: Boolean,
    onDismissMore: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        if (searchExpanded) {
            // Expanded: the full-width "Where to?" bar. Same search behaviour as
            // before — tapping it opens the search screen (or lets the user type).
            Surface(
                // Fixed 48dp height (matching the profile button) so the expanded
                // bar only extends horizontally (weight = fill remaining width) and
                // never grows vertically. A bare heightIn(min=…) let the inner
                // fillMaxHeight() Row expand to the whole screen, turning the bar
                // into a full-height pill. 48dp is also the accessibility minimum.
                modifier = Modifier.weight(1f).height(KccSpacing.s12),
                shape = RoundedCornerShape(KccRadius.full),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSearch()
                },
            ) {
                Row(
                    // fillMaxHeight so the slim content stays centered within the
                    // 48dp minimum touch target rather than pinning to the top.
                    modifier =
                        Modifier.fillMaxHeight()
                            .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.shell_searchIcon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.shell_searchHint),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Collapsed: a round search-icon button in the upper-left, the same
            // size as the profile button. Tapping it expands the full bar. The
            // Spacer pushes the profile button to the upper-right corner.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onExpandSearch()
                },
                modifier = Modifier.size(KccSpacing.s12).testTag(MAP_HOME_SEARCH_TAG),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.shell_searchExpand),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        // This Box is the popup's anchor: composing ProfileMenuPopup inside it
        // hands the button's real measured window bounds to the popup's
        // PopupPositionProvider, so the menu tracks the button wherever the
        // search row lays it out (font scaling, insets, future layout changes).
        Box {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                onClick = onOpenMore,
                modifier = Modifier.size(KccSpacing.s12).testTag(MAP_HOME_MORE_TAG),
            ) {
                // Always render the AccountCircle fallback so the button never
                // shows a blank circle: it covers both the window while the Storage
                // download URL resolves (avatarUrl == null, rememberStorageImageUrl)
                // and the window while Coil fetches/decodes the bitmap (Coil doesn't
                // paint anything until the image is ready). Once the avatar bitmap is
                // displayed, the cropped AsyncImage fills the button and hides it.
                Box(
                    // No clip needed here: the enclosing Surface(shape = CircleShape)
                    // already crops its content (icon + avatar) to the round button.
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = stringResource(R.string.shell_menu),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp).size(28.dp),
                    )
                    if (avatarUrl != null) {
                        // The user's profile picture, drawn on top of the fallback
                        // icon once the bitmap is ready. Decorative: the fallback Icon
                        // underneath already labels the button, so the control keeps
                        // one stable content description in every state.
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            // Profile/account menu: a transparent Popup (no dimming scrim, the map
            // stays visible) anchored to this button's measured bounds; tapping
            // outside (or Back) dismisses.
            if (moreMenuOpen) {
                ProfileMenuPopup(
                    entries = moreMenuEntries,
                    onDismiss = onDismissMore,
                )
            }
        }
    }
}

@Composable
private fun LoadingRoadsChip() {
    Surface(
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.shell_loadingRoads),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ParticipantChip(count: Int) {
    Surface(
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = stringResource(R.string.shell_participantsSharing, count),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun CircleControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    iconRotationDegrees: Float = 0f,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier.size(KccSpacing.s12),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Rotate only the glyph (e.g. the compass north-arrow), not the whole
            // button — a 0 default leaves every other control untouched.
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.rotate(iconRotationDegrees),
            )
        }
    }
}
