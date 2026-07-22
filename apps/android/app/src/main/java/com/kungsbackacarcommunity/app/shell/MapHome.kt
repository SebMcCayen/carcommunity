package com.kungsbackacarcommunity.app.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.MutableState
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
import com.kungsbackacarcommunity.app.design.LocalKccDarkTheme
import com.kungsbackacarcommunity.app.design.LocalKccStatusColors
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.incidents.IncidentTypePickerDialog

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
 * @param canShareLive whether the caller may START a session — i.e. whether the
 *   LIVE_LOCATION feature flag is on. This is NOT a membership check: sharing
 *   your OWN position is FREE (backend parity — live-startSession requires only
 *   an authenticated, non-suspended caller), and only VIEWING others is
 *   member-gated. When false the popup shows a fallback teaser instead of the
 *   start control. Hide-me-now is governed by [isLiveSharing] (not this flag), so
 *   it appears only while a session is already active; the details entry point
 *   stays reachable regardless.
 * @param onStartLiveShare request starting a Single (solo live-sharing) session;
 *   only offered when [canShareLive]. The 1h/2h/4h duration choice is NOT made
 *   here anymore — this hands off to the single-session start flow (the same one
 *   the "+" Create → Single session raises), which is where the duration is
 *   picked. Keeps the broadcast control a one-tap "start sharing" affordance.
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
 *   opens the chat hub (Community / Convoys / Friends + Notifications).
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
    onStartLiveShare: () -> Unit,
    onHideMeNow: () -> Unit,
    onOpenLiveShareDetails: () -> Unit,
    onRecenter: () -> Unit,
    moreMenuEntries: List<HubEntry>,
    modifier: Modifier = Modifier,
    /**
     * True while another bottom-nav tab is drawn over the map.
     *
     * The map home is deliberately NOT disposed when the user leaves the Map tab
     * (that teardown is what used to blank the screen on the way back), so it is
     * still composed — and therefore still live — while something else is on
     * screen. Anything here that reaches outside its own layout has to know it is
     * covered:
     *
     * - The Back handler below deregisters, because a composed `BackHandler` is
     *   registered with the activity's dispatcher no matter what is visible, and
     *   would otherwise eat Back presses that belong to the tab on top.
     * - The transient popups/search collapse, restoring what disposal used to do
     *   implicitly, so returning to the map lands on a clean map.
     *
     * Comes from the shell's single `mapCovered` value, the same one that stands
     * the surface down via [MapSurface.setActive], so the two can't disagree.
     * Defaults to false: callers that only ever show the map (previews, tests)
     * are unaffected.
     */
    covered: Boolean = false,
    unreadChatCount: Int = 0,
    // Tapping the floating chat bubble opens the chat hub
    // (Community / Convoys / Friends + Notifications) as a transparent popup
    // over the map. Defaults to a no-op so existing callers/tests that don't
    // wire chat still compile.
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
    // Whether any of the currently-loaded incidents actually came from
    // Trafikverket (see `hasTrafikverketData`). Gates the "Källa: Trafikverket"
    // credit in the layers popup so it appears exactly where their data is on
    // screen — not, say, on a map in France, where the Sweden-only importer
    // legitimately contributes nothing. Defaults false so callers/tests that do
    // not wire incidents show no credit.
    trafikverketDataShown: Boolean = false,
    /**
     * The shell-owned holder for the manual day/night override (null inside it =
     * follow the app theme).
     *
     * Supplied by the shell so the user's choice outlives this composable, which
     * is disposed whenever a full-screen route opens — see the detailed note at
     * the usage site. Passed as the state holder rather than a value + callback
     * so callers that don't hoist keep working with local state instead of
     * getting an inert toggle.
     */
    nightModeOverrideState: MutableState<MapMode?>? = null,
    // Optional convoy status bar, composed INSIDE the search row between the search
    // control and the profile avatar (see [SearchBarRow]) while — and only while —
    // the caller is in a convoy. A slot rather than convoy parameters so the shell
    // keeps knowing nothing about the convoy domain, and so "not in a convoy" is
    // expressed by the host passing null: nothing is composed at all, rather than
    // an empty bar. Living in the same interactive Row as the search and profile
    // controls means its buttons receive touches exactly like theirs do.
    convoyBar: (@Composable () -> Unit)? = null,
    // Optional convoy awareness layer (member markers + off-screen direction
    // arrows), composed directly ON the map and UNDER all the floating chrome,
    // so an arrow pinned to the top edge tucks behind the search bar rather than
    // covering it. A slot for the same reason [convoyBar] is one: the shell stays
    // ignorant of the convoy domain, and "not in a convoy" is the host passing
    // null, which composes nothing whatsoever.
    //
    // Deliberately NOT inside the map surface: it needs Compose (a remote car
    // photo per member) and it must never intercept map gestures, which is why it
    // carries no pointer input of its own.
    convoyOverlay: (@Composable () -> Unit)? = null,
    // Optional nearby-public live-sharer layer (standalone sharers discovered via
    // live.listNearby, drawn as on-screen markers). Same slot idiom + placement
    // as [convoyOverlay]: a Compose layer ON the map, UNDER the floating chrome,
    // and null when there is nobody nearby (composes nothing). Kept a separate
    // slot from the convoy layer so the two live-marker kinds stay visually
    // distinct and either can be present without the other.
    nearbyOverlay: (@Composable () -> Unit)? = null,
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

    // Day/night follows the APP theme by default: on open the map matches the
    // theme the app is rendering, and it live-updates if that theme changes while
    // the app is open — either because the user picked a different Appearance
    // setting, or, on the Automatic setting, because the system flipped (e.g.
    // Android's scheduled sunset->sunrise dark theme). Once the user flips
    // day/night manually in the layers popup, [desiredMapMode] holds their
    // explicit choice (non-null) and this effect applies it instead of the theme
    // default for the rest of the session. While [desiredMapMode] is null the map
    // follows [systemDefaultMode] — named for the pre-preference behaviour it
    // originally had, but now derived from the resolved app theme.
    // Keyed on mapSurface too (mirrors the setUserMarker effect above) so the
    // *effective* mode is re-applied if the surface instance is swapped (e.g.
    // StubMapSurface -> a real Mapbox-backed surface); a fresh surface starts at
    // its default MapMode, so without this key it would keep that default —
    // dropping the user's manual override — until the theme changed or the user
    // toggled manually again. Applying the effective mode here means the manual
    // choice survives a surface swap.
    //
    // The user's manual day/night override, or null while the map follows the
    // app theme.
    //
    // HOISTED when [nightModeOverrideState] is supplied, and that hoisting is a
    // BUG FIX, not a refactor. Holding this state inside MapHome made the
    // override survive rotation (rememberSaveable) but NOT navigation: the
    // shell composes MapHome in the `else` branch of its route switch, so
    // opening any full-screen route (Settings, Garage, Events, a chat...)
    // removes MapHome from the composition entirely. rememberSaveable only
    // survives recreation of the SAME composition slot — it is discarded on
    // disposal, with no SaveableStateHolder here to retain it — so coming back
    // from a route reset the override to null and the effect below immediately
    // snapped the map to [systemDefaultMode]. Whenever the resolved theme is dark
    // — which, before the Appearance setting existed, simply meant the device was
    // on dark — that read as "the map keeps switching itself back to Night mode as
    // I navigate around the app", which is exactly the reported bug. Owned by the shell
    // (which outlives the route switch), the choice now sticks.
    //
    // Callers that don't hoist (previews, UI tests) fall back to local state and
    // behave exactly as before, so the toggle still works in isolation.
    val desiredMapModeState =
        nightModeOverrideState
            // Still rememberSaveable for the un-hoisted case: survives rotation
            // / activity recreation. MapMode is a simple enum, so a name-based
            // Saver stores the nullable value.
            ?: rememberSaveable(
                stateSaver = Saver(
                    save = { it?.name },
                    // SAFE parse: MapMode.valueOf(...) THROWS on an unknown constant name —
                    // e.g. after an app update that renames/removes an enum value, or
                    // corrupted saved state — which would crash activity recreation.
                    // entries.find returns null for an unknown name (falls back to
                    // "follow the app theme") instead of throwing.
                    restore = { saved -> (saved as? String)?.let { name -> MapMode.entries.find { it.name == name } } },
                ),
            ) { mutableStateOf<MapMode?>(null) }
    var desiredMapMode by desiredMapModeState

    // Follows the APP theme, not the raw system setting: with an explicit
    // Light/Dark preference (Settings -> Appearance) the map's default day/night
    // must match the app the user chose, otherwise picking Light in bright
    // sunshine would still leave a night-styled map. LocalKccDarkTheme is the
    // resolved value KccTheme is actually rendering with, which equals
    // isSystemInDarkTheme() when the preference is Automatic — so the original
    // "live-follow the system sunset->sunrise flip" behaviour is unchanged on
    // the default setting.
    val appInDark = LocalKccDarkTheme.current
    val systemDefaultMode = if (appInDark) MapMode.Night else MapMode.Day
    LaunchedEffect(mapSurface, appInDark, desiredMapMode) {
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

    // Live-location popup open/close is local UI state: tapping the broadcast
    // control opens the transparent live-share sheet (over the map, no scrim),
    // tapping outside it dismisses it.
    var liveOpen by remember { mutableStateOf(false) }

    // Accessible dismiss for the expanded search: the outside-tap scrim is
    // deliberately invisible to TalkBack/keyboard, so system Back is the
    // reliable way to collapse the bar (only intercepts Back while expanded).
    //
    // Also gated on the map being the visible tab. Composition — not visibility —
    // is what registers a BackHandler with the activity's dispatcher, and the map
    // now stays composed under the other tabs, so without `!covered` a search bar
    // left expanded would keep swallowing Back presses from History/Social/Garage.
    // MapHome composes after the shell's own handler and so would win them, making
    // Back look broken (it would collapse a search bar nobody can see instead of
    // returning to the map). This is the guarantee; the reset below is not enough
    // on its own, because it lands a frame later and Back could arrive first.
    BackHandler(enabled = searchExpanded && !covered) { searchExpanded = false }

    // Collapse the map's transient UI as soon as another tab covers it.
    //
    // Until the map started outliving the Map tab, leaving the tab disposed all of
    // this and it came back collapsed; keeping the map composed would silently
    // turn that into "the map is exactly as you left it", which is not obviously
    // better and was never the intent of keeping it alive. These are momentary
    // affordances, not preferences — an expanded "Where to?" bar or a half-open
    // layers sheet is not something a user expects to find waiting on their next
    // visit to the map. So the old behaviour is restored explicitly.
    //
    // Deliberately NOT reset: `desiredMapMode` (the manual day/night override).
    // That IS a preference — it is rememberSaveable precisely so it survives
    // process death, so a tab switch must not drop it.
    LaunchedEffect(covered) {
        if (covered) {
            searchExpanded = false
            moreOpen = false
            layersOpen = false
            liveOpen = false
            reportOpen = false
        }
    }

    // Test hook only — a testTag (not contentDescription) so the internal tag
    // string never leaks into TalkBack. The container itself is decorative.
    //
    // This composable is the map's CHROME, not the map: the surface itself is
    // composed once by the shell, underneath every page, and this draws over it.
    // It deliberately does NOT call [MapSurface.Content] — doing so would put a
    // second MapView call site in a subtree that navigation disposes, which is
    // the blank-flash bug (see MapSurface.Content). Everything here reaches the
    // map through the [mapSurface] hooks instead. The Box stays transparent so
    // the shell's map shows through.
    Box(modifier = modifier.fillMaxSize().testTag(MAP_HOME_TEST_TAG)) {
        // Convoy awareness (member markers + off-screen direction arrows) sits
        // at the BOTTOM of the chrome stack: it belongs to the map, so every
        // control composed below this point draws over it rather than being
        // covered by a car photo pinned to a screen edge.
        convoyOverlay?.invoke()

        // Nearby-public live sharers (discovered via live.listNearby), on the
        // same map layer, just above the convoy layer and still UNDER all the
        // floating chrome.
        nearbyOverlay?.invoke()

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
                // Composed between the search control and the avatar so the convoy
                // pill sits in the gap the two frame, never overlapping either.
                convoyBar = convoyBar,
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
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            val statusColors = LocalKccStatusColors.current
            // 1. Report-incident control — opens the type picker. Shown only
            //    when incident reporting is available (a repository configured);
            //    when it is not, the remaining controls close up by one slot and
            //    live-location leads — no gap, no placeholder holding its place.
            //    Their order relative to each other is what does not change.
            //    Deliberately takes CircleControl's DEFAULT surface/onSurface
            //    colours (like the compass and recenter controls) rather than
            //    the amber warning colour: it is an "open the report picker"
            //    affordance, not a live warning state, and an always-amber
            //    button in a stack of neutral ones reads as a permanent alert.
            //    Colour in this stack is reserved for ACTIVE state (the green
            //    live-share control).
            if (incidentReportingEnabled) {
                CircleControl(
                    icon = Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.incidents_reportButton),
                    onClick = { reportOpen = true },
                    modifier = Modifier.testTag(MAP_HOME_REPORT_TAG),
                )
            }
            // 2. Live-location broadcast control — GREEN when sharing. Opens the
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
            // 3. Map-layers control — opens the transparent layers popup
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
                // The GLYPH always takes the same onSurface colour as every other
                // control in this stack — near-white on the dark theme, ink on the
                // light one. It used to switch to onPrimaryContainer while active,
                // which in the dark theme is crownGold, i.e. a yellow layers icon
                // that matched nothing else on the map. The active state is carried
                // by the container tint above, not by recolouring the icon.
                contentColor = MaterialTheme.colorScheme.onSurface,
                onClick = { layersOpen = true },
                modifier = Modifier.testTag(MAP_HOME_LAYERS_TAG),
            )
            // 4. Compass — a north-arrow rotated by the current map bearing so it
            //    keeps pointing at true north as the map rotates. Tapping it eases
            //    the map back to north-up AND re-centres on the user in ONE camera
            //    move (MapSurface.recenterNorthUp) — resetting bearing alone left
            //    the user looking north at wherever they had panned to, which read
            //    as the button half-working. Sits directly ABOVE the my-location
            //    control because the two now do overlapping things and belong
            //    together as a pair. The built-in Mapbox compass is disabled in
            //    MapboxMapSurface so this is the only compass shown.
            CircleControl(
                icon = Icons.Filled.Navigation,
                contentDescription = stringResource(R.string.shell_compass),
                onClick = { mapSurface.recenterNorthUp() },
                iconRotationDegrees = -bearing,
                modifier = Modifier.testTag(MAP_HOME_COMPASS_TAG),
            )
            // 5. Recenter / my-location — calls MapSurface.recenter(). Re-centres
            //    WITHOUT touching bearing, which is what still separates it from
            //    the compass above: it keeps the map rotated the way the user left
            //    it.
            CircleControl(
                icon = Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.shell_recenter),
                onClick = onRecenter,
            )
            // 6. Chat bubble — opens the chat hub; shows a badge with
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
                trafikverketDataShown = trafikverketDataShown,
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
                onHideMeNow = onHideMeNow,
                onOpenDetails = onOpenLiveShareDetails,
                onDismiss = { liveOpen = false },
            )
        }
    }
}

/** Test tag on the floating map-layers control. */
const val MAP_HOME_LAYERS_TAG = "map_home_layers"

/** Test tag on the floating compass control (directly above the my-location control). */
const val MAP_HOME_COMPASS_TAG = "map_home_compass"

/** Test tag on the collapsed round search button (upper-left). */
const val MAP_HOME_SEARCH_TAG = "map_home_search"

/** Test tag on the floating report-incident control. */
const val MAP_HOME_REPORT_TAG = "map_home_report"

/** Test tag on the map-layers popup card. */
const val MAP_HOME_LAYERS_POPUP_TAG = "map_home_layers_popup"

/** Test tag on the incidents ("Traffic alerts") layer toggle switch. */
const val MAP_HOME_LAYERS_INCIDENTS_TAG = "map_home_layers_incidents"

/** Test tag on the map day/night ("Night mode") layer toggle switch. */
const val MAP_HOME_LAYERS_NIGHT_TAG = "map_home_layers_night"

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
    trafikverketDataShown: Boolean,
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
                    switchTestTag = MAP_HOME_LAYERS_NIGHT_TAG,
                )
                LayerToggleRow(
                    label = stringResource(R.string.shell_layers3d),
                    checked = is3d,
                    onCheckedChange = on3dChange,
                )
                // Attribution for the Trafikverket-sourced incidents drawn on the
                // map layer (product-owner requirement: credit Trafikverket wherever
                // we show their open data). Shown only while the incidents layer is
                // on AND at least one loaded incident actually came from
                // Trafikverket — with the layer off, or abroad where the Sweden-only
                // importer contributes nothing, there is no Trafikverket data on
                // screen to attribute.
                if (incidentsOn && trafikverketDataShown) {
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
 * - Not sharing and [canShareLive]: a single "start sharing" action. The 1h/2h/4h
 *   duration choice is NOT here — [onStart] hands off to the single-session start
 *   flow (shared with the "+" Create → Single session), which is where the
 *   duration is picked. This keeps the map's broadcast control a one-tap start.
 * - Not sharing and not [canShareLive]: the membership teaser (starting is
 *   member-gated on the backend), Stop/Hide stay reachable in the sharing state.
 * A "More options" row always opens the full [com.kungsbackacarcommunity.app.live.LiveLocationScreen]
 * for the complete controls + privacy details. Each action closes the popup.
 *
 * `internal`, not private, because turn-by-turn navigation shows THIS sheet too:
 * a live-location session keeps running while the user navigates, so the
 * controls for it have to stay reachable there — and they must be the same
 * sheet, with the same wording and the same privacy escape hatch, not a second
 * live-sharing UI that can drift from this one.
 */
@Composable
internal fun LiveSharePopup(
    isSharing: Boolean,
    canShareLive: Boolean,
    onStart: () -> Unit,
    onHideMeNow: () -> Unit,
    onOpenDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val statusColors = LocalKccStatusColors.current
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
                        // No Stop here: ending a session is the bottom bar's STOP
                        // sign, so there is ONE stop control and it always raises
                        // the save/discard summary. "Hide me now" stays — it is a
                        // different thing (an immediate privacy escape hatch that
                        // works even while suspended, when stopSession does not).
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
                        // One-tap start: the 1h/2h/4h duration choice has moved to
                        // the single-session start flow (raised by the host), so the
                        // broadcast control no longer picks a duration inline.
                        Button(
                            onClick = {
                                onStart()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.liveLocation_start))
                        }
                    }
                    else -> {
                        // Reached only when the LIVE_LOCATION flag is off (the
                        // server-side kill switch) — starting is NOT member-gated.
                        // TODO: the teaser string still says "membership
                        // required", which is not why the control is hidden.
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
    convoyBar: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        // The flexible left+middle region between the (fixed) profile avatar on the
        // right and nothing on the left. It holds the collapsed search button plus
        // the FULL-WIDTH convoy bar filling the gap; when the search is expanded the
        // "Where to?" field is drawn OVER this region (a later child of the Box =
        // higher z-order), covering the convoy bar rather than sitting beside it.
        // The avatar sits OUTSIDE this Box, so search never covers it.
        Box(modifier = Modifier.weight(1f)) {
            // Base layer: the collapsed round search button (upper-left) + the
            // convoy bar filling the remaining width. The search button is dropped
            // while expanded — the overlay below carries the search affordance then,
            // so there is no duplicate control hiding under it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
            ) {
                if (!searchExpanded) {
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
                }
                // The convoy bar fills the whole gap at full width (never a
                // wrap-content pill); a Spacer holds the gap open when there is no
                // convoy so the avatar stays pinned right, exactly as before.
                if (convoyBar != null) {
                    Box(modifier = Modifier.weight(1f)) { convoyBar() }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Overlay layer: the expanded "Where to?" bar, composed AFTER the base
            // so it is drawn on top of — and fully covers — the convoy bar. Opaque
            // surface, full width of the region, fixed 48dp (matching the avatar and
            // the accessibility minimum) so it only extends horizontally. When
            // search closes the overlay is gone and the convoy bar reappears.
            if (searchExpanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(KccSpacing.s12),
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
            }
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

/**
 * One floating round map control — the shared shape/size/elevation/haptics of
 * the map's right-side stack (report, live-location, layers, compass, recenter,
 * chat, top-to-bottom).
 *
 * `internal`, not private, because the turn-by-turn navigation screen draws the
 * SAME controls (see `navigation/turnbyturn/TurnByTurnNavScreen.kt`): its
 * compass and live-location buttons must be the same control as the map home's,
 * not a look-alike, so a change to the map's control language reaches
 * navigation automatically instead of drifting from it.
 *
 * What is shared is the control LANGUAGE — shape, size, glyph, colour rules —
 * not the stack ORDER or the tap behaviour. Those two deliberately diverge:
 * navigation drives a follow-mode camera, where re-centring means resuming
 * follow and the my-location control only appears once follow has detached, so
 * its compass resets bearing only while the map home's compass also re-centres.
 */
@Composable
internal fun CircleControl(
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
