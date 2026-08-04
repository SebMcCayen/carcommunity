package com.kungsbackacarcommunity.app.navigation.turnbyturn

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kungsbackacarcommunity.app.R
import androidx.lifecycle.Lifecycle
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.diagnostics.FeatureHealthKind
import com.kungsbackacarcommunity.app.diagnostics.FeatureHealthReporter
import com.kungsbackacarcommunity.app.diagnostics.rememberFeatureHealthReporter
import com.kungsbackacarcommunity.app.design.LocalKccDarkTheme
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.incidents.IncidentTypePickerDialog
import com.kungsbackacarcommunity.app.incidents.LocalIncidentAgeFilterController
import com.kungsbackacarcommunity.app.incidents.ReportLocation
import com.kungsbackacarcommunity.app.map.LocalMapZoomController
import com.kungsbackacarcommunity.app.map.MapMarkerStyle
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.shell.ChatCircleControl
import com.kungsbackacarcommunity.app.shell.CircleControl
import com.kungsbackacarcommunity.app.shell.CompassCircleControl
import com.kungsbackacarcommunity.app.shell.IncidentMarkerLayer
import com.kungsbackacarcommunity.app.shell.MapCameraSnapshot
import com.kungsbackacarcommunity.app.shell.MapCircleControlKind
import com.kungsbackacarcommunity.app.shell.MapCompassMode
import com.kungsbackacarcommunity.app.shell.MapControlSet
import com.kungsbackacarcommunity.app.shell.MapIncidentMarker
import com.kungsbackacarcommunity.app.shell.MapLayersPopup
import com.kungsbackacarcommunity.app.shell.MapMode
import com.kungsbackacarcommunity.app.shell.MapProjection
import com.kungsbackacarcommunity.app.shell.MapQueryViewport
import com.kungsbackacarcommunity.app.shell.MapScreenPoint
import com.kungsbackacarcommunity.app.shell.MapboxMapSurface
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.MapboxOptions
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.extension.observable.eventdata.CameraChangedEventData
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.delegates.listeners.OnCameraChangeListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.maps.Style
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.reroute.RerouteController
import com.mapbox.navigation.core.reroute.RerouteState
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.tripdata.speedlimit.api.MapboxSpeedInfoApi
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverPrimaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSecondaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSubOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverViewOptions
import com.mapbox.navigation.ui.components.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.maps.NavigationStyles
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.FollowingFrameOptions
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraTransitionOptions
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Test tag on the whole turn-by-turn navigation view, for UI tests. */
const val TURN_BY_TURN_TEST_TAG = "turn_by_turn_nav"

/**
 * The navigation styles' road-label layer, used as the anchor for BOTH the route
 * line and the congestion overlay: everything we draw goes below the street
 * names, and the route goes above the traffic (see
 * `TurnByTurnEngine.applyTrafficLayers`). One constant because the ordering only
 * works while the two agree on the anchor.
 */
private const val ROUTE_LINE_BELOW_LAYER_ID = "road-label-navigation"

/**
 * Full-screen, Google-Maps-style turn-by-turn navigation view backed by the
 * Mapbox Navigation SDK v3 (aligned with Maps SDK v11.26 — see the `mapboxNav`
 * version catalog entry).
 *
 * Entered from the address-search route preview's "Start" button. It:
 * - follows the user with a tilted, auto-following [NavigationCamera] that
 *   continuously re-frames the route ahead as the location changes,
 * - shows a COMPACT maneuver banner ([MapboxManeuverView]) with the CURRENT turn
 *   — the SDK's own view, styled down (see [applyCompactStyling]) rather than
 *   replaced, and with its tap-to-expand list of subsequent maneuvers disabled,
 * - lets the user pinch-zoom/pan freely (a [NavigationBasicGesturesHandler]
 *   detaches auto-follow on interaction) and offers a "Re-centre" button that
 *   snaps the camera back to following,
 * - exposes a "Report incident/roadwork" affordance wired to [onReportIncident],
 * - shows the driver's current speed, and the POSTED legal limit for the road
 *   whenever the SDK has one (see [NavSpeedInfo]),
 * - keeps the map's compass and live-location controls reachable while driving.
 *
 * ## Chrome parity with the map home
 * Navigation is a MODE of the map, not a different app, so the bottom-right
 * control stack is EXACTLY the map home's: the same controls, in the same order,
 * with the same glyphs, the same colour rules and the same actions. Not more,
 * not fewer, and no navigation-only kinds of button. Which controls and in what
 * order is [MapControlSet.rightSideStack] — one list, rendered by both screens —
 * so the two can no longer drift apart the way they had:
 *
 * - the stack led with the compass instead of the report control;
 * - the compass was a one-way "reset to north" button with the wrong glyph
 *   (the navigation arrow) rather than the map home's two-mode north-up ⇄
 *   course-up toggle. It is now [CompassCircleControl], the same composable;
 * - there was no LAYERS control and no CHAT control at all;
 * - re-centring was a tinted 56.dp [androidx.compose.material3.FloatingActionButton]
 *   that only appeared once follow had detached — a different SIZE and a
 *   different KIND of affordance from everything beside it. It is now the map
 *   home's 48.dp [CircleControl], always present;
 * - and there was a live-broadcast disc the map home no longer has (PR #539
 *   moved starting/stopping/hiding into the centre live control's manage sheet).
 *   It is gone from here too. Consequence, stated plainly: while navigating there
 *   is no bottom bar and therefore no centre live control, so Stop / Hide me now
 *   / Who can see me are reachable only by leaving navigation. Sharing itself is
 *   unaffected — a session started before "Start" keeps running and the puck
 *   still shows it — and "no more, no fewer buttons" is the explicit
 *   instruction, so the button does not come back on its own.
 *
 * The layers popup is the SAME [MapLayersPopup], and every one of its four rows
 * now takes effect HERE: night mode (style URI), traffic (the shared congestion
 * layer), 3D (the follow camera's pitch) and, since this change, "Traffic
 * alerts" — the incident badges. Those were the one toggle with no visible
 * effect while driving, because the badges were drawn by the SHELL surface's
 * annotation layer and the shell surface is stood down the moment navigation
 * starts. This screen now draws them on its own map through the same shared
 * renderer ([com.kungsbackacarcommunity.app.shell.IncidentMarkerLayer]), which
 * is what "I don't see Trafikverket's accidents while navigating" was.
 *
 * The Mapbox SDK's own scale bar (upper-left) and compass (upper-right) are
 * switched OFF here for the same reason
 * [com.kungsbackacarcommunity.app.shell.MapboxMapSurface] switches them off:
 * they are a second, differently-styled set of the same affordances.
 *
 * ## Guarding
 * This file is only compiled when a build-time Mapbox downloads token is present
 * (see app/build.gradle.kts `navSdkEnabled`); the token-less CI build compiles
 * the `src/noNav` stub instead. At RUNTIME it additionally guards on the public
 * `mapbox_access_token` (blank in CI → an "unavailable" panel, no session) and on
 * the fine-location runtime permission (no session until granted) — so no
 * navigation session ever starts without both a token and permission.
 *
 * On-device verification: the live GPS trip session, camera transitions,
 * maneuver rendering and route drawing run only on a token-provisioned device
 * and MUST be verified there; they cannot execute in CI.
 *
 * @param origin resolved route origin (from the search flow's current-location
 *   source); when null the route is requested from the first GPS fix instead.
 * @param destination the chosen destination coordinate.
 * @param destinationLabel human-readable destination name (shown on the exit bar).
 * @param onExit leave the navigation view (back to the map/search).
 * @param onReportIncident report an incident/roadwork of the picked category.
 *   Wired by the host to the SAME `incidents-report` callable the map home uses
 *   (one reporting path, not a navigation-specific copy), so a report filed while
 *   driving is indistinguishable from one filed on the map home.
 * @param incidentReportingEnabled whether the report control is offered at all.
 *   Mirrors the map home's gate (a configured Firebase incidents controller + an
 *   active member); false hides the control rather than showing one that cannot
 *   file anything — and, exactly as on the map home, the rest of the stack then
 *   closes up by one slot.
 * @param incidentsLayerEnabled / [onIncidentsLayerEnabledChange] the "Traffic
 *   alerts" row of the shared layers popup. The value and the callback are the
 *   map home's, so the switch shows and records ONE choice across both screens —
 *   and, since [incidentMarkers] are now drawn here too, it takes effect on THIS
 *   map exactly as it does on the map home (off ⇒ no badges).
 * @param incidentMarkers the crowd-sourced + Trafikverket incident badges, drawn
 *   on THIS map through the SAME renderer the shell surface uses
 *   ([com.kungsbackacarcommunity.app.shell.IncidentMarkerLayer]).
 *
 *   They were absent while navigating for the same structural reason the live
 *   members were: the layer was drawn by the SHELL surface, which is stood down
 *   the moment navigation starts. The host binds the same list the map home is
 *   given, already gated on [incidentsLayerEnabled], so this opens no second
 *   query and shows nothing the map home would not.
 *
 *   Display-only, deliberately: the badges here are not tappable. The incident
 *   detail sheet is composed by the map home, which is not on screen while
 *   navigating, so a tap would open nothing — and poking at map badges is not
 *   something to invite at 90 km/h. Reporting stays available through the round
 *   report control.
 * @param onQueryViewport reports where THIS map is looking, so the host's single
 *   `incidents.listNearby` poll follows the driver instead of the frozen shell
 *   camera. Null when navigation ends, handing the anchor back. It changes only
 *   WHERE the existing poll looks — never how often it runs.
 * @param trafikverketDataShown gates the "Källa: Trafikverket" credit inside the
 *   layers popup, same as on the map home.
 * @param trafficEnabled / [onTrafficEnabledChange] the congestion overlay. The
 *   value comes from the shell surface so both maps agree, and this screen
 *   applies it to its OWN style using the shared traffic source/layer + day-night
 *   ramp ([MapboxMapSurface.addTrafficLayer]).
 * @param nightMode the day/night choice, or null to follow the app theme (the
 *   pre-existing behaviour). Drives BOTH the navigation style URI and the
 *   layers popup's switch. [onNightModeChange] reports a manual flip to the host,
 *   which owns the override for the whole session.
 * @param is3d / [on3dEnabledChange] tilted vs flat. Applied here as the
 *   NAVIGATION camera's pitch override — a follow camera owns its own pitch, so
 *   flattening it is the honest equivalent of the map home's flat 2D camera.
 *   (The map home's other 3D effect, the Standard style's 3D buildings, has no
 *   counterpart: the classic navigation styles do not draw any.)
 * @param unreadChatCount / [onOpenChat] the chat bubble and its unread badge —
 *   the map home's [ChatCircleControl], not a copy. The host raises the same
 *   chat-hub popup it raises from the map.
 * @param liveMembersOverlay the OTHER people sharing a live position — convoy
 *   members and nearby public sharers — drawn on THIS map.
 *
 *   A slot rather than convoy/live parameters, for the same reason [convoyBar] is
 *   one: this screen stays ignorant of both domains. It is handed [MapProjection]
 *   — this screen's own camera + projection — because the whole bug was that the
 *   overlays could only ever be projected against the SHELL's map, which is stood
 *   down the instant navigation starts, so nothing drew them here. The host binds
 *   the SAME roster, the same live listeners and the same entitlement gating it
 *   uses for the map home; null (nobody to draw) composes nothing at all.
 *
 * @param convoyBar the shared convoy status bar
 *   ([com.kungsbackacarcommunity.app.convoy.ConvoyStatusBar]), composed only when
 *   the driver is in a convoy — a convoy does not stop existing because someone
 *   pressed "Start", so the roster and its controls come along.
 *
 *   Placed as the LAST item of the top column, BELOW the maneuver banner rather
 *   than above it. The column stacks, so no position could ever *obscure* the
 *   banner; the question is only what gets pushed down. Inserting the bar above
 *   would shove the current-turn instruction — the one piece of this screen a
 *   driver reads at a glance, at speed — further from the top edge and behind a
 *   piece of social chrome in the reading order. Below the banner it costs the
 *   maneuver nothing, still reads as top chrome, and lands inside the region the
 *   navigation camera already reserves as top padding. It is also rendered in its
 *   `compact` form here (no explanation line), because vertical space beside
 *   safety-critical instructions is not free.
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
@Composable
fun TurnByTurnNavScreen(
    origin: LatLng?,
    destination: LatLng,
    destinationLabel: String,
    onExit: () -> Unit,
    onReportIncident: (IncidentType, ReportLocation) -> Unit,
    modifier: Modifier = Modifier,
    // The Android BACK key while driving. Defaults to a straight [onExit] so
    // existing callers/tests keep the old behaviour; the app wires it to raise
    // an "exit navigation?" confirm instead, so a stray back press cannot drop a
    // driver out of navigation. Kept a host callback (not an in-screen dialog)
    // so the confirm lives in the locally-compilable main module.
    onBackPressed: () -> Unit = onExit,
    incidentReportingEnabled: Boolean = false,
    // The layers popup's four rows, mirroring MapHome's own parameter list one
    // for one. All defaulted so callers/tests that don't wire layers still
    // compile (and get the map's defaults: alerts on, traffic off, 3D on,
    // day/night following the app theme).
    incidentsLayerEnabled: Boolean = true,
    onIncidentsLayerEnabledChange: (Boolean) -> Unit = {},
    incidentMarkers: List<MapIncidentMarker> = emptyList(),
    onQueryViewport: ((MapQueryViewport?) -> Unit)? = null,
    trafikverketDataShown: Boolean = false,
    trafficEnabled: Boolean = false,
    onTrafficEnabledChange: (Boolean) -> Unit = {},
    nightMode: Boolean? = null,
    onNightModeChange: (Boolean) -> Unit = {},
    is3d: Boolean = true,
    on3dEnabledChange: (Boolean) -> Unit = {},
    unreadChatCount: Int = 0,
    onOpenChat: () -> Unit = {},
    // Opens the host's saved-locations picker — the map home's saved-places
    // control, so the same button on the same right-side stack (see
    // [MapControlSet.rightSideStack]).
    onOpenSavedPlaces: () -> Unit = {},
    convoyBar: (@Composable () -> Unit)? = null,
    liveMembersOverlay: (@Composable (MapProjection) -> Unit)? = null,
) {
    val context = LocalContext.current
    val token = stringResource(R.string.mapbox_access_token)

    // Runtime token guard: no session without a token (mirrors rememberMapSurface).
    if (token.isBlank()) {
        NavMessagePanel(
            title = destinationLabel,
            message = stringResource(R.string.turnByTurn_unavailable),
            onExit = onExit,
            modifier = modifier,
        )
        return
    }

    // Runtime fine-location permission guard: no session until granted.
    var hasPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (!hasPermission) {
        NavMessagePanel(
            title = destinationLabel,
            message = stringResource(R.string.turnByTurn_permissionNeeded),
            onExit = onExit,
            modifier = modifier,
        )
        return
    }

    // The single MapView + maneuver banner are created eagerly so the engine can
    // wire the camera/route/maneuver plumbing against them before they attach.
    val mapView =
        remember {
            MapboxOptions.accessToken = token
            MapView(context)
        }
    val maneuverView =
        remember {
            MapboxManeuverView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                applyCompactStyling()
            }
        }
    val lifecycleOwner = LocalLifecycleOwner.current

    // The engine is remembered on (mapView, maneuverView, health) and must STAY
    // that way — adding the lifecycle owner as a key would tear down and rebuild
    // the whole navigation session (trip session, route, camera) just because the
    // host changed. So the owner is read through a rememberUpdatedState instead:
    // the remembered `isForeground` lambda below then samples the CURRENT owner
    // rather than the one captured when the engine was built, which would
    // otherwise be a permanently-DESTROYED lifecycle reporting `foreground=false`
    // for the rest of the session.
    val currentLifecycleOwner by rememberUpdatedState(lifecycleOwner)

    // Feature health: turn-by-turn's failure modes are SILENT. A route request
    // that exhausts its retries leaves the user on a map with no route, no error
    // text and no retry affordance, and nothing throws — so nothing is reported
    // today. These assertions feed the existing auto-issue pipeline; see
    // diagnostics/FeatureHealth.kt for the payload's PII rules and the
    // once-per-session cap. Reaching this point already proves the access token
    // is present (the blank-token guard returned above).
    val health = rememberFeatureHealthReporter(accessTokenPresent = true)
    val engine =
        remember(mapView, maneuverView, health) {
            TurnByTurnEngine(
                mapView = mapView,
                maneuverView = maneuverView,
                origin = origin,
                destination = destination,
                context = context,
                health = health,
                // Sampled at callback time, never captured: a route request can
                // fail long after the user has left the app, and a failure nobody
                // is waiting on must not file an issue.
                isForeground = {
                    currentLifecycleOwner.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.RESUMED)
                },
            )
        }

    // Pick the navigation style from the app's day/night signal so turn-by-turn
    // matches the rest of the UI. Keyed on the theme, so a light/dark flip reloads
    // the map with the matching style.
    //
    // That signal is LocalKccDarkTheme (the darkness KccTheme actually resolved),
    // NOT isSystemInDarkTheme(): with the Appearance preference set to Light or
    // Dark the system value is no longer what the app renders, so reading it here
    // would drop the user into a night-styled navigation screen inside a light
    // app. This screen composes inside AppRoot's KccTheme, so the local is
    // provided. On the default Automatic preference the two are identical, so
    // navigation styling is unchanged for anyone who never opens the setting.
    // The manual override wins when the user has made one (the layers popup's
    // Night switch); otherwise the app theme decides, exactly as before.
    val themeIsDark = LocalKccDarkTheme.current
    val night = nightMode ?: themeIsDark
    val navStyleUri =
        if (night) {
            NavigationStyles.NAVIGATION_NIGHT_STYLE
        } else {
            NavigationStyles.NAVIGATION_DAY_STYLE
        }
    val mapMode = if (night) MapMode.Night else MapMode.Day
    // ── Entry handoff: map home → navigation, without the white flash ────────
    //
    // See [NavHandoff] for the full rationale. In short: this screen builds a
    // SECOND MapView, and a new Mapbox GL surface paints blank frames for the
    // whole of its first style load. Those frames used to be mounted straight
    // over the shell's map — the flash. Now an opaque, Compose-drawn veil fades
    // in over the shell map's last painted frame FIRST, the nav map mounts and
    // loads its style behind it, and the veil fades away onto a finished map.
    val styleLoaded by engine.styleLoaded.collectAsState()
    var phase by remember { mutableStateOf(NavHandoffPhase.VeilIn) }

    // Drive the phases. Each step is a plain delay rather than an animation
    // callback so the sequence cannot stall on a dropped frame.
    LaunchedEffect(engine) {
        delay(NavHandoff.FADE_MILLIS.toLong())
        phase = NavHandoff.afterVeilIn(engine.styleLoaded.value)
    }
    LaunchedEffect(engine, phase, styleLoaded) {
        if (phase != NavHandoffPhase.Loading) return@LaunchedEffect
        if (styleLoaded) {
            phase = NavHandoff.whileLoading(styleLoaded = true, timedOut = false)
            return@LaunchedEffect
        }
        // Style still loading: wait for it, but never past the cap.
        delay(NavHandoff.STYLE_TIMEOUT_MILLIS)
        phase = NavHandoff.whileLoading(styleLoaded = false, timedOut = true)
    }
    LaunchedEffect(phase) {
        if (phase != NavHandoffPhase.Revealing) return@LaunchedEffect
        delay(NavHandoff.FADE_MILLIS.toLong())
        phase = NavHandoffPhase.Ready
    }

    // The style load is started only once the map is actually mounted — starting
    // it earlier would not help (the surface has to exist to render it) and the
    // whole point is that every blank frame lands behind an opaque veil.
    LaunchedEffect(engine, navStyleUri, mapMode, phase.mapMounted) {
        if (phase.mapMounted) engine.loadStyleAndInit(navStyleUri, mapMode)
    }

    // Push the remaining layer choices at the map. Each is idempotent and a
    // no-op until the style is up (the engine also re-applies all of them from
    // these same remembered values on every style (re)load, so a day/night flip
    // cannot silently drop the traffic layer or the flat camera).
    LaunchedEffect(engine, trafficEnabled, mapMode) {
        engine.setTrafficEnabled(trafficEnabled, mapMode)
    }
    LaunchedEffect(engine, is3d) { engine.set3dEnabled(is3d) }

    DisposableEffect(engine, lifecycleOwner) {
        // Feature health: if the nav session cannot be stood up at all, the whole
        // screen is dead — no route, no guidance, no error. Cheap to assert and
        // wrapped rather than restructured, so the happy path is untouched. The
        // throwable itself is deliberately NOT reported: an exception message is
        // the classic carrier of a path or identifier into a world-readable
        // issue, and the kind alone is what triage needs.
        val setupOk =
            runCatching {
                if (!MapboxNavigationApp.isSetup()) {
                    MapboxNavigationApp.setup(NavigationOptions.Builder(context).build())
                }
                MapboxNavigationApp.attach(lifecycleOwner)
            }.isSuccess
        if (!setupOk) {
            health.report(
                kind = FeatureHealthKind.NavSessionInitFailed,
                foreground =
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                surfaceShown = true,
            )
        }
        val observer =
            object : MapboxNavigationObserver {
                @SuppressLint("MissingPermission")
                override fun onAttached(mapboxNavigation: MapboxNavigation) {
                    engine.attach(mapboxNavigation)
                }

                override fun onDetached(mapboxNavigation: MapboxNavigation) {
                    engine.detach(mapboxNavigation)
                }
            }
        // Guarded for the same reason the setup above is: once `setupOk` is false
        // we are knowingly proceeding against a MapboxNavigationApp that may not
        // be in the state it expects, and a throw here would crash the screen we
        // just finished reporting as degraded. Registering anyway (rather than
        // returning early) is deliberate — MapboxNavigationApp attaches queued
        // observers if it is set up later, so a transient setup failure can still
        // recover into a working session, whereas an early return would disable
        // navigation for the lifetime of this composition.
        runCatching { MapboxNavigationApp.registerObserver(observer) }
        onDispose {
            // Order matters: detach the app FIRST so this lifecycle owner leaving
            // fires the observer's onDetached → TurnByTurnEngine.detach() (which
            // unregisters the route/location/progress observers AND stops the trip
            // session). Unregistering the observer before detaching would suppress
            // onDetached and leak those observers + the running trip session.
            //
            // The two SDK calls are individually guarded so that one throwing
            // cannot skip the other — and, more importantly, cannot skip
            // engine.cancel()/mapView.onDestroy() below. Those two are OURS and
            // are not optional: the MapView is created in `remember` outside this
            // effect, so failing to destroy it leaks a GL surface for the rest of
            // the process. Teardown must always complete.
            runCatching { MapboxNavigationApp.detach(lifecycleOwner) }
            runCatching { MapboxNavigationApp.unregisterObserver(observer) }
            engine.cancel()
            mapView.onDestroy()
        }
    }

    val progress by engine.progress.collectAsState()
    val rerouting by engine.rerouting.collectAsState()
    val bearing by engine.bearing.collectAsState()
    val speed by engine.speed.collectAsState()

    // Whether the SDK is actually GUIDING. The first route-progress tick is what
    // puts the maneuver banner and the ETA on screen, so it is also the moment
    // the destination pill stops being the only thing naming the trip — see
    // [NavTopChrome.destinationBarVisible] for why the pill goes away then and
    // not sooner. `progress` is cleared again when the session detaches, so the
    // pill comes back rather than being suppressed for good.
    val guidanceActive = progress != null
    val destinationBarVisible = NavTopChrome.destinationBarVisible(guidanceActive)

    // Keep the follow camera's top padding matching the chrome that is actually
    // drawn. Hiding the pill frees exactly its height, and a padding left behind
    // would reserve screen for something no longer there — sliding the puck down
    // by that much. Idempotent, so this never forces a camera move on its own.
    LaunchedEffect(engine, destinationBarVisible) {
        engine.setDestinationBarVisible(destinationBarVisible)
    }

    // The incident badges, on THIS map. Gated exactly as the map home gates them
    // (`MapHome`'s LaunchedEffect(mapSurface, incidentMarkers, incidentsLayerEnabled)):
    // the layer switched off pushes an EMPTY list rather than freezing the last
    // one, so flipping it while driving takes the badges off immediately.
    LaunchedEffect(engine, incidentMarkers, incidentsLayerEnabled) {
        engine.setIncidentMarkers(if (incidentsLayerEnabled) incidentMarkers else emptyList())
    }

    // Tell the host where this map is looking, so its ONE incident poll follows
    // the driver instead of the shell camera frozen at the trip's origin.
    //
    // A slow local sample rather than a subscription to the camera: this map's
    // camera changes on essentially every frame while driving, and re-reading
    // the visible bounds 60 times a second to answer a question that is asked
    // once every 15 s would be pure waste. It issues no query of its own — the
    // poll's cadence is entirely the host's, which is what keeps a rate-limited
    // callable rate-limited.
    //
    // Read through rememberUpdatedState, not as an effect key: the host passes a
    // fresh lambda on every recomposition, so keying on it would restart the
    // sampler (and re-report immediately) for every unrelated state change up
    // there. The loop is keyed on the ENGINE, which is the thing whose viewport
    // is being reported.
    val currentQueryViewportSink by rememberUpdatedState(onQueryViewport)
    LaunchedEffect(engine) {
        while (true) {
            currentQueryViewportSink?.invoke(engine.queryViewport())
            delay(NAV_QUERY_VIEWPORT_SAMPLE_MILLIS)
        }
    }
    // Leaving navigation hands the anchor back to the shell map. Separate from
    // the effect above because a cancelled coroutine cannot report anything.
    DisposableEffect(engine) {
        onDispose { currentQueryViewportSink?.invoke(null) }
    }

    // Incident-report category picker open/close — local, transient UI state, the
    // same shape as the map home's `reportOpen` (a plain `remember`, deliberately
    // NOT saveable: a half-made report should not survive process death).
    var reportOpen by remember { mutableStateOf(false) }

    // Map-layers popup open/close — same shape as the map home's `layersOpen`.
    var layersOpen by remember { mutableStateOf(false) }

    // Compass orientation, exactly the map home's two-mode toggle — but seeded
    // COURSE-UP rather than north-up, because that is what this camera actually
    // does. A navigation follow camera rotates the map to the direction of
    // travel; opening on the north-up glyph would be a compass that disagrees
    // with the map underneath it from the first frame. Tapping still toggles the
    // pair, and north-up sticks for as long as the user holds it.
    //
    // rememberSaveable with a name-based Saver, matching the map home's: a future
    // rename that drops an enum constant falls back to a valid mode instead of
    // crashing activity recreation the way MapCompassMode.valueOf would.
    var compassMode by rememberSaveable(
        stateSaver =
            Saver(
                save = { it.name },
                restore = { saved ->
                    (saved as? String)
                        ?.let { name -> MapCompassMode.entries.find { it.name == name } }
                        ?: MapCompassMode.CourseUp
                },
            ),
    ) { mutableStateOf(MapCompassMode.CourseUp) }

    // Keep the navigation camera's bearing behaviour in sync with the chosen
    // mode (mirrors the map home's LaunchedEffect(mapSurface, compassMode)).
    // Idempotent, so this never forces a camera move on open.
    LaunchedEffect(engine, compassMode) { engine.setCompassMode(compassMode) }

    // BACK while the live nav view is up routes through the host, which raises
    // the "exit navigation?" confirm rather than leaving immediately. The error/
    // permission NavMessagePanel states keep their direct onExit — there is no
    // active route there to guard.
    BackHandler { onBackPressed() }

    Box(modifier = modifier.fillMaxSize().testTag(TURN_BY_TURN_TEST_TAG)) {
        // Mounted only once the veil is opaque (see [NavHandoffPhase.mapMounted]).
        // Mounting it during the fade-in is precisely what let the blank GL
        // frames show through, because a MapView is SurfaceView-backed and is
        // punched through the window rather than composited with the veil above
        // it — it cannot be hidden by alpha, only by not being there yet.
        if (phase.mapMounted) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        }

        // Other people sharing a live position — convoy members (with their
        // off-screen direction arrows) and nearby public sharers. Composed
        // directly over the map and UNDER every piece of floating chrome, the
        // same placement and the same reasoning as on the map home: a car photo
        // pinned to a screen edge must tuck behind the maneuver banner and the
        // control stack, not cover them.
        //
        // Only once the map is mounted: the overlay projects through the engine,
        // and before the MapView exists there is no camera to project with (the
        // engine returns null and the overlay draws nothing anyway — this just
        // avoids composing it to find that out).
        if (phase.mapMounted) {
            liveMembersOverlay?.invoke(engine)
        }

        // Top: destination pill (pre-guidance only) + maneuver banner.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(KccSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            // The destination pill: the searched-for place name and a back arrow,
            // in the upper-left corner.
            //
            // Gone once guidance starts. It was reported as "you will still see
            // the search result in the upper left corner… if you have started a
            // navigation there is no need to see it", and that is exactly right:
            // once the banner is naming the next turn and the bottom bar is
            // naming the arrival time, a pill repeating the search result is
            // covering road for nothing. It is suppressed for the ACTIVE state
            // only — before the first route-progress tick there is no banner and
            // no ETA, so this is the sole thing saying where the trip is going,
            // and it returns if the session ever stops guiding.
            //
            // Leaving navigation does not depend on it: the ETA bar's Exit
            // button, the system back gesture and the screen's own BackHandler
            // all remain, which is why removing an affordance here is safe.
            if (destinationBarVisible) {
                Surface(
                    shape = RoundedCornerShape(KccRadius.full),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                    modifier = Modifier.testTag(TURN_BY_TURN_DESTINATION_BAR_TAG),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // Tapping it (arrow or label) leaves navigation, same as
                        // the back gesture and the bottom "Exit" button.
                        // Role.Button + the exit label make it an announced,
                        // activatable control for a11y.
                        modifier = Modifier
                            .clickable(
                                onClickLabel = stringResource(R.string.turnByTurn_exit),
                                role = Role.Button,
                                onClick = onExit,
                            )
                            .padding(horizontal = KccSpacing.s3, vertical = KccSpacing.s2),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.turnByTurn_exit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = KccSpacing.s2),
                        )
                        Text(
                            text = destinationLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            AndroidView(factory = { maneuverView }, modifier = Modifier.fillMaxWidth())

            // Brief "Rerouting…" indicator, shown only while the SDK is fetching a
            // replacement route after the user deviates; cleared once the new route
            // is active (reroute state leaves FetchingRoute) or the session ends.
            if (rerouting) {
                Surface(
                    shape = RoundedCornerShape(KccRadius.full),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                        modifier = Modifier.padding(
                            horizontal = KccSpacing.s3,
                            vertical = KccSpacing.s2,
                        ),
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.turnByTurn_rerouting),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            // Convoy status bar, LAST so the maneuver banner keeps its place at
            // the top of the screen (see the [convoyBar] KDoc). Composed only
            // while the driver is actually in a convoy.
            convoyBar?.invoke()
        }

        // Bottom chrome, stacked as ONE column so nothing can overlap: the speed
        // readout + control stack sit in a row ABOVE the progress bar rather than
        // floating over it. (The controls used to be centre-right, which put the
        // compass in a different place from the map home's — see the KDoc.)
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(KccSpacing.s3),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Bottom-LEFT: current speed, and the posted limit when known.
                SpeedReadout(speed = speed)
                Spacer(modifier = Modifier.weight(1f))
                // Bottom-RIGHT: the map home's control stack, verbatim — same
                // controls, same order, same glyphs, same colour rules. See the
                // file KDoc for what this used to be and why each difference was
                // a bug rather than a deliberate divergence.
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                ) {
                    // The SAME list the map home renders, so "which buttons, in
                    // what order" has exactly one answer. Exhaustive `when`: a new
                    // control kind fails to compile here until this screen draws
                    // it, which is the point.
                    MapControlSet
                        .rightSideStack(incidentReportingEnabled)
                        .forEach { control ->
                            when (control) {
                                // Report incident/roadwork — the map home's
                                // control, not a navigation-specific one, opening
                                // the SHARED category picker whose choice goes to
                                // the host's single reporting path.
                                MapCircleControlKind.Report ->
                                    CircleControl(
                                        icon = Icons.Filled.Warning,
                                        contentDescription =
                                            stringResource(R.string.incidents_reportButton),
                                        onClick = { reportOpen = true },
                                        modifier = Modifier.testTag(TURN_BY_TURN_REPORT_TAG),
                                    )

                                // Map layers — the map home's control opening the
                                // map home's popup. The "active" tint follows the
                                // same rule: traffic on, OR the user has manually
                                // DEVIATED from the theme's day/night default, OR
                                // 3D is off. A system-driven Night (dark theme,
                                // untouched) must not light it up, which is why
                                // the night term tests `nightMode != null` first.
                                MapCircleControlKind.Layers -> {
                                    val layersActive =
                                        trafficEnabled ||
                                            (nightMode != null && nightMode != themeIsDark) ||
                                            !is3d
                                    CircleControl(
                                        icon = Icons.Filled.Layers,
                                        contentDescription =
                                            stringResource(R.string.shell_layersButton),
                                        containerColor =
                                            if (layersActive) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        onClick = { layersOpen = true },
                                        modifier = Modifier.testTag(TURN_BY_TURN_LAYERS_TAG),
                                    )
                                }

                                // Compass — the map home's two-mode toggle, the
                                // same composable. NORTH-UP pins the follow
                                // camera's bearing to 0; COURSE-UP releases it so
                                // the camera goes back to turning the map into the
                                // direction of travel. Both also resume following,
                                // because on this screen an orientation the camera
                                // is not driving is not an orientation at all.
                                MapCircleControlKind.Compass ->
                                    CompassCircleControl(
                                        compassMode = compassMode,
                                        bearing = bearing,
                                        onModeChange = { next ->
                                            compassMode = next
                                            // Apply now for immediacy, exactly like
                                            // the map home's control; the effect
                                            // above re-syncs a frame later and
                                            // setCompassMode is idempotent.
                                            engine.setCompassMode(next)
                                        },
                                        modifier = Modifier.testTag(TURN_BY_TURN_COMPASS_TAG),
                                    )

                                // Saved places — the map home's control, opening
                                // the host's saved-locations picker. Same button,
                                // same stack, so navigation is not a different app.
                                MapCircleControlKind.SavedPlaces ->
                                    CircleControl(
                                        icon = Icons.Filled.Place,
                                        contentDescription =
                                            stringResource(R.string.shell_savedPlacesButton),
                                        onClick = onOpenSavedPlaces,
                                        modifier = Modifier.testTag(TURN_BY_TURN_SAVED_PLACES_TAG),
                                    )

                                // Chat bubble + unread badge — the map home's
                                // control. The host raises the same chat-hub popup.
                                MapCircleControlKind.Chat ->
                                    ChatCircleControl(
                                        unreadCount = unreadChatCount,
                                        onClick = onOpenChat,
                                    )
                            }
                        }
                }
            }

            // Bottom: ETA / remaining progress bar with an explicit exit action.
            NavProgressBar(progress = progress, onExit = onExit)
        }

        // The SHARED map-layers popup from the map home — the same composable,
        // so navigation offers the same rows, in the same order, with the same
        // wording and the same Trafikverket credit. Every switch reads and writes
        // the HOST's state, so a choice made while driving is the same choice the
        // map home shows afterwards.
        if (layersOpen) {
            // Same ambient resting-zoom preference as the map home. Changing it
            // while driving does not move the navigation SDK's own camera, but it
            // persists — so it is the same choice the map home shows afterwards,
            // exactly like the layer toggles above.
            val mapZoomController = LocalMapZoomController.current
            // Same ambient Trafikverket alert max-age filter as the map home, for
            // the same reason as the zoom above: a choice made while driving
            // persists and is the same the map home shows afterwards.
            val incidentAgeFilterController = LocalIncidentAgeFilterController.current
            MapLayersPopup(
                incidentsOn = incidentsLayerEnabled,
                onIncidentsChange = onIncidentsLayerEnabledChange,
                trafikverketDataShown = trafikverketDataShown,
                trafficOn = trafficEnabled,
                onTrafficChange = onTrafficEnabledChange,
                // The switch shows the EFFECTIVE mode (manual override, else the
                // theme), matching the map home, so it never disagrees with the
                // map behind it while an override is unset.
                nightMode = night,
                onNightModeChange = onNightModeChange,
                is3d = is3d,
                on3dChange = on3dEnabledChange,
                browsingZoom = mapZoomController.browsingZoom,
                onBrowsingZoomChange = { mapZoomController.setBrowsingZoom(it) },
                incidentMaxAge = incidentAgeFilterController.maxAge,
                onIncidentMaxAgeChange = { incidentAgeFilterController.setMaxAge(it) },
                onDismiss = { layersOpen = false },
            )
        }

        // The SHARED incident category picker from the map home — the same
        // composable, so navigation offers the same categories in the same order
        // with the same wording. Picking a category hands the choice straight to
        // the host, which routes it to the one `incidents-report` callable.
        //
        // Behind the wheel we deliberately keep the fast CURRENT-location path
        // (no "pick on map" step): a driver reporting what they're passing wants
        // one tap, not a map to fiddle with. The map picker is a map-home
        // affordance; navigation always reports [ReportLocation.Current].
        if (reportOpen) {
            IncidentTypePickerDialog(
                onPick = { type ->
                    reportOpen = false
                    onReportIncident(type, ReportLocation.Current)
                },
                onDismiss = { reportOpen = false },
            )
        }

        // ── The handoff veil ────────────────────────────────────────────────
        //
        // Drawn LAST so it covers the map and this screen's chrome together:
        // navigation arrives as one dissolve rather than a map swap with
        // buttons appearing over it.
        //
        // Its colour is the app's own surface for the CURRENT theme — the same
        // day/night signal that picks the navigation style URI — so the
        // transition never passes through white on a dark map. That was the
        // reported symptom, and a hardcoded light veil would simply have
        // reintroduced it after dark.
        //
        // While it IS up it swallows every pointer event, the same way an
        // opaque page drawn over the shell map does. An opaque
        // overlay that does not block input is worse than no overlay: a Box with
        // only `background`/`alpha` registers no pointer-input modifier, so taps
        // and drags fall straight through to the chrome and the map beneath it —
        // the user would be hitting the exit bar, the report control or a pan
        // gesture they cannot see, for up to STYLE_TIMEOUT_MILLIS on a slow
        // style load.
        //
        // Once the transition is over it is not composed AT ALL, so it cannot go
        // on eating gestures meant for the map — the mistake PR #464 fixed,
        // where an always-present Surface swallowed every one of them.
        if (phase.veilVisible) {
            // An explicit Animatable seeded at NavHandoff.VEIL_START_ALPHA, NOT
            // animateFloatAsState: that initialises to its first target, and the
            // first target here is the opaque 1f of VeilIn — so it would snap
            // straight to opaque and only the fade-OUT would ever animate. The
            // dissolve away from the map home is half the point, so the starting
            // value has to be stated rather than inferred.
            val veil = remember { Animatable(NavHandoff.VEIL_START_ALPHA) }
            LaunchedEffect(phase.veilTargetAlpha) {
                veil.animateTo(
                    targetValue = phase.veilTargetAlpha,
                    animationSpec = tween(NavHandoff.FADE_MILLIS),
                )
            }
            val veilAlpha = veil.value
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .alpha(veilAlpha)
                        .background(MaterialTheme.colorScheme.surface)
                        // Consumed on the Main pass, which children see first;
                        // the veil has no children, so this simply ends every
                        // gesture here instead of letting it reach a sibling.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
            )
        }
    }
}

/** Test tag on the navigation speed readout (current speed + posted limit). */
const val TURN_BY_TURN_SPEED_TEST_TAG = "turn_by_turn_speed"

/** Test tag on the navigation view's round "report incident/roadwork" control. */
const val TURN_BY_TURN_REPORT_TAG = "turn_by_turn_report"

/** Test tag on the navigation view's round map-layers control. */
const val TURN_BY_TURN_LAYERS_TAG = "turn_by_turn_layers"

/** Test tag on the navigation view's round compass control. */
const val TURN_BY_TURN_COMPASS_TAG = "turn_by_turn_compass"

/** Test tag on the navigation view's round saved-places control. */
const val TURN_BY_TURN_SAVED_PLACES_TAG = "turn_by_turn_saved_places"

/**
 * Test tag on the top-left destination ("search result") pill, which is composed
 * only before guidance starts — see [NavTopChrome.destinationBarVisible].
 */
const val TURN_BY_TURN_DESTINATION_BAR_TAG = "turn_by_turn_destination_bar"

/**
 * How often the navigation map reports its viewport to the host's incident poll.
 *
 * A local camera read, NOT a query: at 5 s the anchor is at worst one sample
 * stale when the 15 s `incidents.listNearby` keep-alive fires, which at Swedish
 * motorway speed is a couple of hundred metres inside a query radius measured in
 * kilometres. Deliberately far cheaper than subscribing to the camera, which on
 * this screen changes on essentially every frame.
 */
private const val NAV_QUERY_VIEWPORT_SAMPLE_MILLIS = 5_000L

/**
 * Shrinks the SDK's maneuver banner in place.
 *
 * The complaint was "show the next turn, but make the next turn window much
 * smaller as it takes up a lot of space". The banner is NOT replaced — every
 * change here goes through [MapboxManeuverView]'s own public styling, so the SDK
 * keeps owning what the banner says and how it renders shields, exit numbers,
 * lane guidance and sub-maneuvers. Only the size changes.
 *
 * Three things make it big, and all three are addressed:
 *
 * 1. **The expandable list.** Tapping anywhere on the banner expands a 200 dp
 *    list of the maneuvers AFTER the current one (`mapbox_maneuver_layout.xml`).
 *    Disabling `upcomingManeuverRenderingEnabled` both removes it and disarms
 *    the whole-view click that opens it — a stray touch while driving can no
 *    longer double the banner's height. The CURRENT turn, which is what was
 *    asked for, is unaffected.
 * 2. **The type.** 30 sp primary / 24 sp secondary / 18 sp sub / 22 sp step
 *    distance, replaced by the compact appearances in
 *    `res/values/nav_maneuver_styles.xml`. Those styles set SIZE only, so the
 *    SDK's own day/night text colours survive.
 * 3. **The turn icon**, 48 dp with a 12 dp top margin, which sets the banner's
 *    floor height regardless of the type — shrink the text alone and the card
 *    barely moves. It is reached by id through the library's own public R class
 *    (the same route [com.mapbox.navigation.ui.components.R] is already used for
 *    the puck drawable), because the view exposes no styling hook for its size.
 *    Null-safe and wrapped: an SDK version that renames the id leaves a
 *    full-size icon, never a crash.
 *
 * Everything here is idempotent and done once, at construction.
 */
private fun MapboxManeuverView.applyCompactStyling() {
    upcomingManeuverRenderingEnabled = false
    runCatching {
        updateManeuverViewOptions(
            ManeuverViewOptions.Builder()
                .primaryManeuverOptions(
                    ManeuverPrimaryOptions.Builder()
                        .textAppearance(R.style.KccNavManeuverPrimaryCompact)
                        .build(),
                )
                .secondaryManeuverOptions(
                    ManeuverSecondaryOptions.Builder()
                        .textAppearance(R.style.KccNavManeuverSecondaryCompact)
                        .build(),
                )
                .subManeuverOptions(
                    ManeuverSubOptions.Builder()
                        .textAppearance(R.style.KccNavManeuverSubCompact)
                        .build(),
                )
                .stepDistanceTextAppearance(R.style.KccNavManeuverStepDistanceCompact)
                .build(),
        )
    }
    runCatching {
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val icon: View? = findViewById(com.mapbox.navigation.ui.components.R.id.maneuverIcon)
        icon?.let { view ->
            val params = view.layoutParams ?: return@let
            params.width = px(NavManeuverCompact.TURN_ICON_DP)
            params.height = px(NavManeuverCompact.TURN_ICON_DP)
            (params as? ViewGroup.MarginLayoutParams)?.topMargin =
                px(NavManeuverCompact.TURN_ICON_TOP_MARGIN_DP)
            view.layoutParams = params
        }
    }
}

/**
 * The bottom-left speed readout: the driver's CURRENT speed always, and the
 * POSTED legal limit beside it only when the SDK actually has one.
 *
 * Deliberately inert. It reports two numbers and nothing else — no colour
 * change, no warning, no chime, no score when the current speed exceeds the
 * limit. A speeding alert or any gamification of speed is an explicit product
 * NO, so this composable has no notion of the two numbers being compared.
 *
 * When [NavSpeedInfo.postedLimitKmh] is null (the common case on smaller Swedish
 * roads, where Mapbox coverage thins out) the limit sign is simply absent — the
 * readout degrades to the current speed alone rather than showing a stale or
 * guessed number.
 *
 * A null [speed] means no location fix has arrived YET (the state the screen
 * opens in), not "no speed". It renders as 0 km/h rather than as nothing: the
 * readout is fixed nav chrome, and a gap where it belongs during the seconds
 * before the first fix reads as a broken HUD, then shoves the rest of the
 * bottom row sideways when the number pops in. 0 is also the honest reading for
 * a car that has just started — it matches [NavSpeedInfo.currentKmh]'s own
 * contract, which already maps a missing GPS speed to 0 for exactly this
 * reason, so the readout never blanks once it is on screen either.
 */
@Composable
private fun SpeedReadout(
    speed: NavSpeedInfo?,
    modifier: Modifier = Modifier,
) {
    val shown = speed ?: NavSpeedInfo(currentKmh = 0, postedLimitKmh = null)
    // TalkBack reads ONE phrase per element ("Current speed 72 km/h", "Speed
    // limit 50 km/h") rather than the bare glyphs, which on their own are two
    // context-free numbers. clearAndSetSemantics replaces the children's nodes
    // instead of adding to them, so the digits aren't announced twice.
    val currentDescription = stringResource(R.string.turnByTurn_currentSpeed, shown.currentKmh)
    Row(
        modifier = modifier.testTag(TURN_BY_TURN_SPEED_TEST_TAG),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        Surface(
            shape = RoundedCornerShape(KccRadius.full),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.clearAndSetSemantics { contentDescription = currentDescription },
        ) {
            Column(
                modifier =
                    Modifier.padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = shown.currentKmh.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = NavSpeedFormat.KMH_LABEL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val limit = shown.postedLimitKmh
        if (limit != null) {
            val limitDescription = stringResource(R.string.turnByTurn_speedLimit, limit)
            // The posted-limit sign, drawn in the European (Vienna Convention)
            // idiom Sweden uses: a red ring around a white disc with the number
            // inside. Red is the SIGN's colour, not a warning about the driver's
            // speed — it looks the same at 30 km/h under a 30 limit as it does
            // at 90.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                modifier =
                    Modifier
                        .size(KccSpacing.s12)
                        .border(3.dp, MaterialTheme.colorScheme.error, CircleShape)
                        .clearAndSetSemantics { contentDescription = limitDescription },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = limit.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavProgressBar(
    progress: NavProgress?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unitM = stringResource(R.string.addressSearch_unitMeters)
    val unitKm = stringResource(R.string.addressSearch_unitKilometers)
    val unitMin = stringResource(R.string.addressSearch_unitMinutes)
    val unitH = stringResource(R.string.addressSearch_unitHours)

    Surface(
        shape = RoundedCornerShape(topStart = KccRadius.xl, topEnd = KccRadius.xl),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                if (progress != null) {
                    val arrival =
                        NavProgressFormat.arrivalClock(
                            progress.durationRemainingSeconds,
                            Instant.now(),
                            ZoneId.systemDefault(),
                        )
                    Text(
                        text = stringResource(R.string.turnByTurn_arrive, arrival),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text =
                            NavProgressFormat.remaining(
                                progress,
                                stringResource(R.string.turnByTurn_remaining),
                                unitM,
                                unitKm,
                                unitMin,
                                unitH,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.addressSearch_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onExit) {
                Text(stringResource(R.string.turnByTurn_exit))
            }
        }
    }
}

@Composable
private fun NavMessagePanel(
    title: String,
    message: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onExit() }
    Box(
        modifier = modifier.fillMaxSize().testTag(TURN_BY_TURN_TEST_TAG).padding(KccSpacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
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

/**
 * Holds and drives the Mapbox Navigation SDK v3 plumbing for one navigation
 * session: the [NavigationCamera] + viewport data source, the route line/arrow
 * renderers, the maneuver API feeding the [MapboxManeuverView], and the location
 * puck. Bound to a [MapboxNavigation] via [attach]/[detach]. Every native call is
 * wrapped defensively so a partial/failed frame degrades rather than crashing.
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
private class TurnByTurnEngine(
    private val mapView: MapView,
    private val maneuverView: MapboxManeuverView,
    private val origin: LatLng?,
    private val destination: LatLng,
    private val context: Context,
    /**
     * Feature-health sink for the SILENT nav failures — the ones that leave the
     * screen looking fine but doing nothing. Reports carry the failure KIND only:
     * never the destination, the origin, the route, or a router error message.
     */
    private val health: FeatureHealthReporter,
    /** Whether the app is in front of the user, sampled at report time. */
    private val isForeground: () -> Boolean,
) : MapProjection {
    /** Latest progress snapshot for the bottom ETA bar (null until the first tick). */
    private val progressFlow = MutableStateFlow<NavProgress?>(null)
    val progress: StateFlow<NavProgress?> = progressFlow.asStateFlow()

    /**
     * True while an off-route reroute is in flight (SDK is fetching a replacement
     * route), driving the "Rerouting…" indicator. Set on
     * [RerouteState.FetchingRoute] and cleared on every other state — the
     * fetched/idle/interrupted/failed transitions all mean nothing is in flight.
     */
    private val reroutingFlow = MutableStateFlow(false)
    val rerouting: StateFlow<Boolean> = reroutingFlow.asStateFlow()

    /**
     * Live camera bearing in degrees (0 = north-up), driving the compass needle.
     * Mirrors [com.kungsbackacarcommunity.app.shell.MapboxMapSurface]'s bearing
     * flow exactly — including the rounding to whole degrees, which stops the
     * per-frame fractional jitter of a rotating navigation camera from
     * recomposing the compass on every frame. During navigation this rotates
     * constantly (the following camera is course-up), so the dedupe matters more
     * here than it does on the map home.
     */
    private val bearingFlow = MutableStateFlow(0f)
    val bearing: StateFlow<Float> = bearingFlow.asStateFlow()

    /**
     * The settled camera, for the live-member overlays drawn over this map (see
     * [MapProjection]). Rounded by [MapCameraSnapshot.of] and de-duplicated by
     * the flow, which matters far more here than on the map home: a following
     * navigation camera moves on essentially every frame, and an un-rounded
     * snapshot would re-project every member marker 60 times a second.
     */
    private val cameraSnapshotFlow = MutableStateFlow<MapCameraSnapshot?>(null)
    override val cameraSnapshot: StateFlow<MapCameraSnapshot?> = cameraSnapshotFlow.asStateFlow()

    /**
     * Project a coordinate into this map's pixel space — the navigation map's
     * OWN projection, so members land where the road under them actually is at
     * this zoom, rotation and (steep) navigation pitch.
     */
    override fun screenPositionFor(latitude: Double, longitude: Double): MapScreenPoint? {
        return runCatching {
            val screen =
                mapView.mapboxMap.pixelForCoordinate(Point.fromLngLat(longitude, latitude))
            MapScreenPoint(x = screen.x.toFloat(), y = screen.y.toFloat())
        }.getOrNull()
    }

    /**
     * Current speed + posted limit, or null until the first location fix.
     *
     * The posted limit is whatever the SDK reports for the matched road and is
     * very often absent — see [NavSpeedFormat.postedLimitKmh], which turns every
     * uncertain case into a null so the UI hides the sign instead of showing a
     * number that might be wrong.
     */
    private val speedFlow = MutableStateFlow<NavSpeedInfo?>(null)
    val speed: StateFlow<NavSpeedInfo?> = speedFlow.asStateFlow()

    /**
     * Whether this map has finished its FIRST style load and is painting real
     * content — the signal that ends the entry handoff (see [NavHandoff]).
     *
     * A brand-new `MapView` paints blank frames until its style is up, which is
     * what the white flash was. The screen keeps an opaque veil over the map
     * until this turns true, so those frames are never presented.
     *
     * Deliberately latching: it is set true on the first successful style load
     * and never reset. A later day/night style reload does drop back to blank
     * frames briefly, but re-running the whole entry dissolve mid-drive would be
     * more disruptive than the flicker it hides, so the veil is an entry
     * transition only.
     */
    private val styleLoadedFlow = MutableStateFlow(false)
    val styleLoaded: StateFlow<Boolean> = styleLoadedFlow.asStateFlow()

    /**
     * Identifies the most recent [loadStyleAndInit] request, so a completing
     * load can tell whether it is still the one being waited on.
     *
     * A system light/dark flip re-keys the caller's effect and starts a SECOND
     * style load, which can overlap the first — and the first one completing
     * would otherwise report "the map is painting real content" while the map is
     * in fact mid-way through loading the newer style, revealing the veil onto
     * exactly the blank frames it exists to hide. Only the newest request is
     * allowed to end the handoff.
     *
     * Main-thread only, like the rest of this class's mutable state: both the
     * callers (Compose effects) and Mapbox's style callback run there.
     */
    private var styleLoadToken = 0

    private val viewportDataSource = MapboxNavigationViewportDataSource(mapView.mapboxMap)
    private val navigationCamera =
        NavigationCamera(mapView.mapboxMap, mapView.camera, viewportDataSource)

    private val maneuverApi =
        MapboxManeuverApi(MapboxDistanceFormatter(DistanceFormatterOptions.Builder(context).build()))

    /**
     * Route-line API with the VANISHING line enabled, which is what gives the
     * travelled/remaining split: the SDK keeps a vanishing point on the geometry
     * and paints everything behind it in the traveled colour. It is not on by
     * default.
     */
    private val routeLineApi =
        MapboxRouteLineApi(
            MapboxRouteLineApiOptions.Builder()
                .vanishingRouteLineEnabled(true)
                .build(),
        )

    /**
     * The renderer for those layers, holding the palette for the CURRENT
     * day/night mode.
     *
     * A `var` rebuilt in [loadStyleAndInit], because the colours are baked into
     * the layer paint properties when the layers are created and the view's
     * options are immutable. That is safe precisely because the only thing that
     * changes them is a day/night flip, and a day/night flip already reloads the
     * whole style (dropping every layer) — so the rebuild lands exactly where
     * the layers are about to be recreated anyway.
     */
    private var routeLineView: MapboxRouteLineView = buildRouteLineView(MapMode.Day)

    /** The mode [routeLineView] was built for; null until the first style load. */
    private var routeLineMode: MapMode? = null

    private fun buildRouteLineView(mode: MapMode): MapboxRouteLineView {
        val colors = NavRouteLinePalette.forNight(mode == MapMode.Night)
        // Every congestion class takes the SAME colour as the rest of the route.
        // The road you must drive has to read as one continuous ribbon — that is
        // the whole request — and a per-segment green/amber/red ramp would both
        // break it up and duplicate the map's own traffic overlay, which is a
        // layer the user can now switch on from here. Closures and restricted
        // sections DO keep their own colour: they are properties of the road that
        // change whether you can use it, not a speed reading. Nothing here is
        // derived from how fast the driver is going.
        val colorResources =
            RouteLineColorResources.Builder()
                .routeDefaultColor(colors.remaining)
                .routeCasingColor(colors.remainingCasing)
                .routeLineTraveledColor(colors.traveled)
                .routeLineTraveledCasingColor(colors.traveledCasing)
                .routeLowCongestionColor(colors.remaining)
                .routeModerateCongestionColor(colors.remaining)
                .routeHeavyCongestionColor(colors.remaining)
                .routeSevereCongestionColor(colors.remaining)
                .routeUnknownCongestionColor(colors.remaining)
                .routeClosureColor(colors.closure)
                .restrictedRoadColor(colors.restricted)
                .build()
        return MapboxRouteLineView(
            MapboxRouteLineViewOptions.Builder(context)
                // Under the navigation styles' road labels, so street names stay
                // readable over the route rather than being painted out by it.
                .routeLineBelowLayerId(ROUTE_LINE_BELOW_LAYER_ID)
                .routeLineColorResources(colorResources)
                .build(),
        )
    }

    private val routeArrowApi = MapboxRouteArrowApi()
    private val routeArrowView = MapboxRouteArrowView(RouteArrowOptions.Builder(context).build())

    private val navigationLocationProvider = NavigationLocationProvider()

    /**
     * Derives the POSTED speed limit at the user's current location from each
     * location-matcher result. This is the SDK's supported entry point for it
     * (`tripdata`), and it returns null whenever there is no speed information
     * for the matched road — which is most of the time off the main network.
     */
    private val speedInfoApi = MapboxSpeedInfoApi()

    /**
     * Unit/format options handed to [speedInfoApi]. Built once: the options only
     * describe formatting, and rebuilding them per fix would allocate on every
     * ~1 Hz location tick for nothing.
     */
    private val distanceFormatterOptions = DistanceFormatterOptions.Builder(context).build()

    /**
     * Draws the single end-of-route destination marker. Created once the style
     * is loaded (annotation managers need a style) and re-created if the style
     * reloads, e.g. on a day/night flip.
     */
    private var destMarkerManager: CircleAnnotationManager? = null

    /** Camera-change listener feeding [bearingFlow]; held so it can be detached. */
    private var cameraChangeListener: OnCameraChangeListener? = null

    // The layer/camera choices the screen has pushed, remembered so every one of
    // them can be RE-APPLIED after a style reload. A style reload drops the
    // traffic layer entirely, and the pitch/bearing overrides live on the
    // viewport data source rather than the style — keeping the values here is
    // what stops a day/night flip mid-drive from silently turning traffic off or
    // un-flattening a camera the user flattened.
    private var trafficEnabled = false
    private var trafficMode = MapMode.Day
    private var threeDEnabled = true
    private var compassMode = MapCompassMode.CourseUp

    /**
     * Whether the destination pill is currently drawn above the maneuver banner.
     * Part of the camera's top padding, so it lives with the other remembered
     * choices rather than being read back off the composition.
     */
    private var destinationBarVisible = true

    // ── The incident layer's state ──────────────────────────────────────────
    //
    // All of it belongs to THIS map. The renderer itself
    // ([com.kungsbackacarcommunity.app.shell.IncidentMarkerLayer]) is shared with
    // the shell surface and holds nothing, which is precisely what lets one
    // implementation serve two live maps.

    /** This map's own annotation manager; recreated on every style load. */
    private var incidentMarkerManager: PointAnnotationManager? = null

    /**
     * Marker images already uploaded to the CURRENT style. Cleared with the
     * manager, because style images die with the style they were added to.
     */
    private val registeredIncidentImages = mutableSetOf<String>()

    /**
     * Annotation id → incident id, rebuilt by every draw.
     *
     * Written but never read here, deliberately: this layer is display-only
     * (no click listener — see the screen's [incidentMarkers] KDoc), and the
     * shared renderer maintains the lookup for the caller that DOES hit-test.
     * Kept rather than passed a throwaway map so the two callers hand the
     * renderer the same shape of state and a future tappable navigation badge
     * has the lookup it would need already correct.
     */
    private val incidentIdsByAnnotation = mutableMapOf<String, String>()

    /** The badges the screen last pushed, redrawn after each style (re)load. */
    private var incidentMarkers: List<MapIncidentMarker> = emptyList()

    /** The last COMPLETE draw, so an unchanged set is not redrawn. */
    private var lastAppliedIncidents: List<MapIncidentMarker>? = null

    private var firstFixReceived = false
    private var routeRequested = false

    // Bounded route-request retries. A failed request resets routeRequested to
    // false; the next location fix retries (with the explicit origin if we have
    // one, else the current fix). We cap total attempts so a persistently failing
    // route doesn't spam the routing service on every ~1 Hz location tick.
    private var routeRequestAttempts = 0
    private val maxRouteRequestAttempts = 3

    init {
        // ── Keep the puck on the centre line ────────────────────────────────
        //
        // Reported as "the screen is not centering on my location — my location
        // is a little bit to the right, even when pressing the GPS or north
        // button". The padding below was already left/right symmetric, so it was
        // not the cause; the SDK's framing rules were.
        //
        // `FollowingFrameOptions.maximizeViewableGeometryWhenPitchZero` defaults
        // to TRUE, and its contract is explicit: when a following frame has
        // pitch 0 and there are at least two points to frame, "the puck will not
        // be tied to the bottom edge of the followingPadding and instead move
        // around the CENTROID of the framed geometry". The same class documents
        // that the focal point "has no effect when the camera is framing
        // maneuver and maximizeViewableGeometryWhenPitchZero is enabled".
        //
        // That is not a rare state on this screen:
        // - `FollowingFrameOptions.pitchNearManeuvers` also defaults to enabled,
        //   with a 180 m trigger distance, so the frame flattens to pitch 0 on
        //   the approach to every turn that is not a continue/merge/ramp/fork;
        // - and with 3D switched OFF in the layers popup, `set3dEnabled` pins
        //   the following pitch at 0 for the WHOLE trip.
        //
        // In both cases the puck slides off toward whichever side lets the road
        // ahead fill more of the screen. Re-centring cannot fix it, which is
        // exactly what was reported: `recenter()` and the compass's north/course
        // toggle both just re-request the SAME following frame, so they
        // recompute the same off-centre position.
        //
        // Switching the maximisation off keeps the puck pinned to the focal
        // point at every pitch. The focal point is then stated rather than
        // inherited: x = 0.5 is the horizontal centre of the padded box (which,
        // with equal side padding, is the screen's centre line), y = 1.0 its
        // bottom edge — the standard navigation position, showing the road ahead
        // rather than the road behind. Both are the SDK's own defaults; naming
        // them means a future default change cannot quietly move the puck.
        runCatching {
            viewportDataSource.options.followingFrameOptions.apply {
                maximizeViewableGeometryWhenPitchZero = false
                focalPoint = FollowingFrameOptions.FocalPoint(0.5, 1.0)
            }
        }
        applyCameraPadding()

        // Detach auto-follow when the user pans/zooms; the state observer then
        // reveals the re-centre button.
        mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(navigationCamera),
        )
        // NOTE there is deliberately no camera-state observer here any more. It
        // existed only to HIDE the re-centre button while the camera was already
        // following — a control that came and went mid-drive, which is exactly the
        // divergence from the map home this change removes. The control is now
        // always on screen, and re-arming follow when follow is already on is a
        // harmless no-op, so there is nothing left to observe.

        // Drop the SDK's built-in scale bar — the distance/km ruler it draws in
        // the UPPER LEFT. It is pure chrome during turn-by-turn (the bottom bar
        // already states the real remaining distance) and it is the only thing
        // occupying that corner. The map home disables it for the same reason.
        runCatching { mapView.scalebar.updateSettings { enabled = false } }
        // Drop the SDK's built-in compass (upper right, its own glyph). The
        // compass below is the map home's control, in the map home's place —
        // leaving this one enabled would put two differently-styled compasses on
        // one screen.
        runCatching { mapView.compass.updateSettings { enabled = false } }

        // Mirror the live camera bearing into the flow so the compass needle
        // keeps pointing at true north as the following camera swings the map.
        val camListener =
            object : OnCameraChangeListener {
                @Suppress("UNUSED_PARAMETER")
                override fun onCameraChanged(eventData: CameraChangedEventData) {
                    val camera = mapView.mapboxMap.cameraState
                    bearingFlow.value = camera.bearing.toFloat().roundToInt().toFloat()
                    // Same de-duplication argument as the bearing, applied to the
                    // whole camera: the live-member overlays re-project every
                    // marker when this changes, so it is rounded to about a metre
                    // / a hundredth of a zoom / a whole degree and StateFlow
                    // collapses the settled frames. Mirrors MapboxMapSurface.
                    cameraSnapshotFlow.value =
                        MapCameraSnapshot.of(
                            latitude = camera.center.latitude(),
                            longitude = camera.center.longitude(),
                            zoom = camera.zoom,
                            bearing = camera.bearing,
                            pitch = camera.pitch,
                        )
                }
            }
        cameraChangeListener = camListener
        runCatching { mapView.mapboxMap.addOnCameraChangeListener(camListener) }

        // Navigation puck fed by the SDK's enhanced location.
        runCatching {
            mapView.location.apply {
                setLocationProvider(navigationLocationProvider)
                locationPuck =
                    LocationPuck2D(
                        bearingImage =
                            ImageHolder.from(
                                com.mapbox.navigation.ui.components.R.drawable.mapbox_navigation_puck_icon,
                            ),
                    )
                puckBearingEnabled = true
                enabled = true
            }
        }
    }

    /**
     * Loads the given navigation style (day or night, chosen by the caller from
     * the app theme or the layers popup's override) and (re)builds everything
     * that lives on a style: the route-line layers, the congestion overlay and
     * the destination marker.
     */
    fun loadStyleAndInit(styleUri: String, mode: MapMode) {
        // Retire the previous marker manager BEFORE the new style is loaded,
        // while its own style is still the current one. A manager cannot be
        // carried across a style reload: in Maps SDK 11.26.0 the annotation
        // plugin's `onStyleChanged` is an empty method and AnnotationManagerImpl
        // registers no style-loaded listener of its own, so nothing re-adds its
        // GeoJSON source and layer to the new style — a reused manager would
        // hold a live annotation the map no longer draws. Recreating is
        // therefore correct; dropping the old reference on the floor was not.
        // `removeAnnotationManager` is the disposal the plugin exposes: it drops
        // the manager from the plugin's own manager list and calls its
        // `onDestroy`, which removes the associated layers and sources from the
        // style, clears the style images and annotation maps, and unregisters
        // the map interactions the manager registered. Only the annotations
        // were being cleaned up before, so those map-level registrations and
        // list entries accumulated one set per day/night flip.
        releaseDestMarkerManager()
        // Same argument, same moment, for the incident layer: its manager and
        // its uploaded style images both belong to the style about to be
        // replaced. Retired here and rebuilt against the new style below.
        releaseIncidentMarkerManager()
        // Rebuild the route-line renderer when the day/night palette changes.
        // Its colours are baked into the layer paint properties at creation, and
        // the style load below is about to drop and recreate those layers, so
        // this is the one moment at which a new palette can take effect without
        // fighting layers that already exist. `routeLineMode == null` on the very
        // first load, so the initial palette is always applied here rather than
        // being whatever the field was seeded with.
        if (routeLineMode != mode) {
            runCatching { routeLineView.cancel() }
            routeLineView = buildRouteLineView(mode)
            routeLineMode = mode
        }
        trafficMode = mode
        // Claim this request. Anything still in flight from an earlier one is
        // now stale and must not be allowed to end the handoff.
        val token = ++styleLoadToken
        val loaded =
            runCatching {
                mapView.mapboxMap.loadStyle(styleUri) { style ->
                    // Traffic BEFORE the route line: both anchor below the same
                    // label layer, and whatever is inserted last ends up nearest
                    // it — so adding congestion first leaves the route drawn on
                    // top of it, which is the ordering a driver needs.
                    applyTrafficLayers(style)
                    runCatching { routeLineView.initializeLayers(style) }
                    // Re-draw the ROUTE onto the fresh style.
                    //
                    // This is the fix for "I can see the maneuver arrow but the
                    // road I should drive isn't coloured". The route line was
                    // rendered from exactly one place — the routes observer — and
                    // that render is dropped whenever `mapboxMap.style` is null.
                    // On this screen it very often is: the route request is issued
                    // from attach() on the FIRST composition, while the style load
                    // does not even start until the handoff veil is opaque
                    // (NavHandoff.FADE_MILLIS later). A route that arrives inside
                    // that window was silently discarded and nothing ever asked
                    // for it again, because routes only change on a reroute. The
                    // maneuver ARROW kept working throughout because it is
                    // re-rendered on every route-progress tick — which is exactly
                    // the reported symptom. The same gap swallowed the route on
                    // every day/night style reload.
                    //
                    // getRouteDrawData replays whatever the API is currently
                    // holding, so a style that comes up after the route simply
                    // gets it now. It is a no-op when there is no route yet.
                    runCatching {
                        routeLineApi.getRouteDrawData { value ->
                            runCatching { routeLineView.renderRouteDrawData(style, value) }
                        }
                    }
                    // Re-apply the camera overrides the layers popup owns; these
                    // live on the viewport data source, not the style, but a
                    // reload is the moment everything else is being restored so
                    // they are restored with it.
                    applyCameraOverrides()
                    // Create the destination-marker manager against the freshly
                    // loaded style and redraw the marker. A style reload (day/night
                    // flip) drops every annotation, so the marker has to be redrawn
                    // here rather than only once at startup.
                    runCatching {
                        destMarkerManager = mapView.annotations.createCircleAnnotationManager()
                        drawDestinationMarker()
                    }
                    // The incident layer, on ITS OWN annotation manager — the
                    // same separation the shell surface keeps between incidents,
                    // crowns and event pins, so the layers can be drawn, emptied
                    // and torn down independently of one another and of the
                    // destination dot. Drawn from the CURRENT list, so badges
                    // that arrived while the style was still loading are
                    // rendered rather than lost, and redrawn here after every
                    // day/night reload (which drops every annotation).
                    //
                    // No click listener, deliberately: the incident detail sheet
                    // is composed by the map home, which is not on screen while
                    // navigating, so a tap would open nothing.
                    runCatching {
                        val manager = mapView.annotations.createPointAnnotationManager()
                        IncidentMarkerLayer.configure(manager)
                        incidentMarkerManager = manager
                        lastAppliedIncidents = null
                        applyIncidentMarkersIfChanged(incidentMarkers)
                    }
                    // The map now has a style and is painting real content, so the
                    // handoff veil covering it can be faded away. Set LAST, after
                    // the route line and destination marker are in place, so the
                    // reveal never lands on a basemap that is still missing the
                    // route the user just asked for.
                    //
                    // Only if this is still the CURRENT request: a theme flip
                    // during the handoff starts a newer load, and letting this
                    // older one report success would reveal the veil onto the
                    // newer style's blank frames — the flash, reintroduced.
                    if (token == styleLoadToken) styleLoadedFlow.value = true
                }
            }.isSuccess
        // A throw here means no style load was ever started, so the callback above
        // will never run and would strand the veil until its timeout. Release it
        // now: a map with no style is a bad screen, but a permanently veiled one
        // is a dead screen. Token-guarded for the same reason as the success
        // path — a newer request in flight is still going to report for itself.
        if (!loaded && token == styleLoadToken) styleLoadedFlow.value = true
    }

    /**
     * Disposes the current destination-marker manager, if any, and clears the
     * reference. Safe to call when there is none.
     */
    private fun releaseDestMarkerManager() {
        val manager = destMarkerManager ?: return
        destMarkerManager = null
        runCatching { manager.deleteAll() }
        runCatching { mapView.annotations.removeAnnotationManager(manager) }
    }

    /**
     * Draws the end-of-route destination dot.
     *
     * Without it the route simply STOPS: the line ends mid-map with nothing
     * marking the goal, which reads as a truncated route rather than an arrival
     * point. Styled from the shared [MapMarkerStyle] so it is the same dot the
     * route preview showed a moment earlier, on the "Start" tap before this
     * screen opened — the destination must not change appearance across that
     * transition.
     *
     * The destination is fixed for the lifetime of this engine, so this always
     * clears first and draws exactly one annotation (a reroute keeps the same
     * destination and must not stack a second dot on it).
     */
    private fun drawDestinationMarker() {
        val manager = destMarkerManager ?: return
        runCatching { manager.deleteAll() }
        runCatching {
            manager.create(
                CircleAnnotationOptions()
                    .withPoint(Point.fromLngLat(destination.longitude, destination.latitude))
                    .withCircleRadius(MapMarkerStyle.DEST_MARKER_RADIUS)
                    .withCircleColor(MapMarkerStyle.DEST_MARKER_COLOR)
                    .withCircleStrokeWidth(MapMarkerStyle.DEST_MARKER_STROKE)
                    .withCircleStrokeColor(MapMarkerStyle.DEST_MARKER_STROKE_COLOR),
            )
        }
    }

    private val locationObserver =
        object : LocationObserver {
            override fun onNewRawLocation(rawLocation: Location) = Unit

            override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
                val enhanced = locationMatcherResult.enhancedLocation
                navigationLocationProvider.changePosition(
                    location = enhanced,
                    keyPoints = locationMatcherResult.keyPoints,
                )
                viewportDataSource.onLocationChanged(enhanced)
                viewportDataSource.evaluate()

                // Move the travelled/remaining split with the car. Route progress
                // alone updates it, but only at the SDK's progress cadence and
                // only in whole steps; feeding the matched position keeps the
                // boundary sitting on the puck instead of trailing behind it.
                // Cheap: one call per ~1 Hz fix, and a no-op before there is a
                // route or a style.
                runCatching {
                    val style = mapView.mapboxMap.style
                    if (style != null) {
                        val update =
                            routeLineApi.updateTraveledRouteLine(
                                Point.fromLngLat(enhanced.longitude, enhanced.latitude),
                            )
                        routeLineView.renderRouteLineUpdate(style, update)
                    }
                }

                // Speed readout. The CURRENT speed is taken straight from the
                // enhanced fix (m/s → km/h) rather than from the SDK's formatted
                // value, so it is in the app's units unconditionally and does not
                // disappear when the SDK has no speed-limit data for the road.
                //
                // The POSTED limit comes from the speed-info API and is null far
                // more often than not; the whole call is wrapped because a native
                // throw here would kill the location observer — and with it the
                // camera, the route retry and the puck — over a decorative
                // readout. On failure the limit is simply absent.
                val postedLimit =
                    runCatching {
                        val info =
                            speedInfoApi.updatePostedAndCurrentSpeed(
                                locationMatcherResult,
                                distanceFormatterOptions,
                            )
                        NavSpeedFormat.postedLimitKmh(
                            speed = info?.postedSpeed,
                            unitName = info?.postedSpeedUnit?.name,
                        )
                    }.getOrNull()
                speedFlow.value =
                    NavSpeedInfo(
                        currentKmh = NavSpeedFormat.currentKmhFromMetersPerSecond(enhanced.speed),
                        postedLimitKmh = postedLimit,
                    )

                if (!firstFixReceived) {
                    firstFixReceived = true
                    navigationCamera.requestNavigationCameraToFollowing(
                        stateTransitionOptions =
                            NavigationCameraTransitionOptions.Builder().maxDuration(0).build(),
                    )
                }
                // Request (or retry) the route on a location fix while none is in
                // flight and we're under the attempt cap. This covers BOTH the
                // no-origin case (first request comes from the first fix) AND
                // retrying a failed explicit-origin request — which attach()'s
                // immediate request can't retry on its own. We prefer the explicit
                // origin when set, else the current fix, so a route from the
                // intended start point is preserved across retries.
                if (!routeRequested && routeRequestAttempts < maxRouteRequestAttempts) {
                    val originPoint =
                        origin?.let { Point.fromLngLat(it.longitude, it.latitude) }
                            ?: Point.fromLngLat(enhanced.longitude, enhanced.latitude)
                    requestRoute(originPoint)
                }
            }
        }

    private val routeProgressObserver =
        RouteProgressObserver { routeProgress ->
            viewportDataSource.onRouteProgressChanged(routeProgress)
            viewportDataSource.evaluate()

            val style = mapView.mapboxMap.style
            if (style != null) {
                runCatching {
                    val arrowUpdate = routeArrowApi.addUpcomingManeuverArrow(routeProgress)
                    routeArrowView.renderManeuverUpdate(style, arrowUpdate)
                }
                // Advance the route line's vanishing point, which is what
                // de-emphasises the part already driven and keeps the coloured
                // remainder honest across leg changes and reroutes. Without this
                // the line is drawn once and never updated again.
                runCatching {
                    routeLineApi.updateWithRouteProgress(routeProgress) { update ->
                        runCatching { routeLineView.renderRouteLineUpdate(style, update) }
                    }
                }
            }

            // Maneuver banner: primary (current) turn + upcoming maneuvers after it.
            runCatching {
                val maneuvers = maneuverApi.getManeuvers(routeProgress)
                // fold here is only a success-gate: renderManeuvers() takes the
                // Expected<ManeuverError, List<Maneuver>> itself, so the success
                // branch deliberately ignores its unwrapped value (`_`) and passes
                // the outer `maneuvers` through; the error branch is a no-op.
                maneuvers.fold({ /* ignore maneuver errors */ }, { _ ->
                    maneuverView.renderManeuvers(maneuvers)
                })
            }

            progressFlow.value =
                NavProgress(
                    distanceRemainingMeters = routeProgress.distanceRemaining.toDouble(),
                    durationRemainingSeconds = routeProgress.durationRemaining.toDouble(),
                )
        }

    private val routesObserver =
        RoutesObserver { result ->
            if (result.navigationRoutes.isNotEmpty()) {
                routeLineApi.setNavigationRoutes(result.navigationRoutes) { value ->
                    mapView.mapboxMap.style?.let { style ->
                        runCatching { routeLineView.renderRouteDrawData(style, value) }
                    }
                }
                viewportDataSource.onRouteChanged(result.navigationRoutes.first())
                viewportDataSource.evaluate()
            } else {
                mapView.mapboxMap.style?.let { style ->
                    routeLineApi.clearRouteLine { value ->
                        runCatching { routeLineView.renderClearRouteLineValue(style, value) }
                    }
                    runCatching { routeArrowView.render(style, routeArrowApi.clearArrows()) }
                }
                viewportDataSource.clearRouteData()
                viewportDataSource.evaluate()
            }
        }

    // Off-route re-routing. The Nav SDK v3 default reroute controller is active by
    // default: once a trip session is running with a set route, deviating from it
    // triggers the SDK to recompute a route from the current location to the same
    // destination and swap it in — which arrives through routesObserver (reason
    // ROUTES_UPDATE_REASON_REROUTE) and continues the session automatically, so no
    // manual off-route detection or re-request is needed here. We only OBSERVE the
    // reroute state to surface a brief "Rerouting…" indicator: FetchingRoute means
    // a reroute is in flight; any other state (RouteFetched/Idle/Interrupted/Failed)
    // means it isn't.
    private val rerouteStateObserver =
        RerouteController.RerouteStateObserver { state ->
            reroutingFlow.value = state is RerouteState.FetchingRoute
        }

    private var mapboxNavigation: MapboxNavigation? = null

    @SuppressLint("MissingPermission")
    fun attach(mapboxNavigation: MapboxNavigation) {
        this.mapboxNavigation = mapboxNavigation
        mapboxNavigation.registerRoutesObserver(routesObserver)
        mapboxNavigation.registerLocationObserver(locationObserver)
        mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
        // Observe the (default, already-active) reroute controller for the UI
        // indicator. getRerouteController() is null-safe: if no controller is set
        // the indicator simply never shows, but the SDK's built-in reroute still runs.
        mapboxNavigation.getRerouteController()?.registerRerouteStateObserver(rerouteStateObserver)
        // Real GPS trip session (permission is guarded by the caller).
        mapboxNavigation.startTripSession()
        // If we already have an origin, request the route immediately.
        origin?.let { requestRoute(Point.fromLngLat(it.longitude, it.latitude)) }
    }

    fun detach(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterRoutesObserver(routesObserver)
        mapboxNavigation.unregisterLocationObserver(locationObserver)
        mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNavigation.getRerouteController()?.unregisterRerouteStateObserver(rerouteStateObserver)
        // Clear any lingering "Rerouting…" indicator when the session ends.
        reroutingFlow.value = false
        // Drop the speed readout too: with the trip session stopped no further
        // fixes arrive, so leaving the last value on screen would show a frozen
        // speed (and a limit for a road the user may no longer be on).
        speedFlow.value = null
        // And the route progress, for the same reason plus one more: it is what
        // "guidance is active" is derived from, so leaving a stale value here
        // would keep the destination pill suppressed on a screen that is no
        // longer guiding — the pill is hidden while navigating, not for good.
        progressFlow.value = null
        // Mirror attach()'s startTripSession(): stop the live GPS trip session so
        // exiting the screen ends location updates instead of leaving them running
        // (battery/GPS drain). stopTripSession() is a safe no-op if not started, so
        // detach() stays idempotent.
        runCatching { mapboxNavigation.stopTripSession() }
        this.mapboxNavigation = null
    }

    /** Snap the camera back to following the user. */
    fun recenter() {
        navigationCamera.requestNavigationCameraToFollowing()
    }

    /**
     * Apply the map home's compass toggle to the NAVIGATION camera.
     *
     * The map home eases its free camera to a bearing; this camera is a FOLLOW
     * camera that recomputes its own bearing from the route on every fix, so
     * easing it would be undone within the second. The equivalent — and the only
     * thing that actually sticks — is an override on the viewport data source
     * the follow camera reads from:
     * - NORTH-UP pins the following bearing at 0, so true north stays up while
     *   the camera goes on tracking the car along the route;
     * - COURSE-UP clears the override, handing the bearing back to the SDK, which
     *   is the course-up behaviour navigation opens in.
     *
     * Both then re-request following, because an orientation the camera is not
     * driving is not an orientation at all — which also matches the map home,
     * whose compass re-centres as well as rotating. Idempotent: re-applying the
     * current mode moves nothing.
     */
    fun setCompassMode(mode: MapCompassMode) {
        compassMode = mode
        applyCameraOverrides()
        runCatching { navigationCamera.requestNavigationCameraToFollowing() }
    }

    /**
     * The congestion overlay, toggled from the shared layers popup. Uses the
     * SAME source, tileset and day/night ramp as the map home
     * ([MapboxMapSurface.addTrafficLayer]) so the two maps cannot show different
     * traffic. A no-op until the style is up; re-applied on every style load.
     */
    fun setTrafficEnabled(enabled: Boolean, mode: MapMode) {
        trafficEnabled = enabled
        trafficMode = mode
        runCatching { mapView.mapboxMap.style?.let { applyTrafficLayers(it) } }
    }

    /**
     * Tilted vs flat, toggled from the shared layers popup.
     *
     * On the map home this flips the camera pitch AND hides the Standard style's
     * 3D buildings. The classic navigation styles draw no 3D objects at all, so
     * here it is the pitch alone — expressed, like the compass, as an override on
     * the follow camera rather than a one-off camera move that the next fix would
     * discard.
     */
    fun set3dEnabled(enabled: Boolean) {
        threeDEnabled = enabled
        applyCameraOverrides()
    }

    /**
     * Push the pitch/bearing overrides the layers popup and the compass own at
     * the viewport data source. Called from the setters AND from every style
     * load, so a day/night reload cannot quietly restore a tilt or a rotation the
     * user turned off.
     */
    private fun applyCameraOverrides() {
        runCatching {
            // null hands the property back to the SDK's own computation; a value
            // pins it. Overview shares the pitch so flattening is not silently
            // undone the moment the camera zooms out to show the whole route.
            viewportDataSource.followingPitchPropertyOverride(if (threeDEnabled) null else 0.0)
            viewportDataSource.overviewPitchPropertyOverride(if (threeDEnabled) null else 0.0)
            viewportDataSource.followingBearingPropertyOverride(
                when (compassMode) {
                    MapCompassMode.NorthUp -> 0.0
                    MapCompassMode.CourseUp -> null
                },
            )
            viewportDataSource.evaluate()
        }
    }

    /**
     * Tell the camera whether the destination pill is on screen, so its top
     * padding matches the chrome that is actually drawn.
     *
     * Idempotent — re-applying the current value recomputes the same padding, so
     * this can be called from a Compose effect on every recomposition without
     * ever nudging the camera.
     */
    fun setDestinationBarVisible(visible: Boolean) {
        if (destinationBarVisible == visible) return
        destinationBarVisible = visible
        applyCameraPadding()
        runCatching { viewportDataSource.evaluate() }
    }

    /**
     * Push the follow/overview padding computed by [NavCameraPadding] at the
     * viewport data source.
     *
     * The dp → device-pixel conversion happens here because `EdgeInsets` takes
     * device pixels; the reasoning about which strips of screen are covered is
     * in [NavCameraPadding], which is pure and unit-tested (this file cannot be:
     * it only compiles with the Navigation SDK on the classpath).
     */
    private fun applyCameraPadding() {
        runCatching {
            val density = mapView.resources.displayMetrics.density
            val following =
                NavCameraPadding.following(
                    maneuverBannerHeightDp = NavManeuverCompact.HEIGHT_DP,
                    destinationBarVisible = destinationBarVisible,
                )
            val overview =
                NavCameraPadding.overview(
                    maneuverBannerHeightDp = NavManeuverCompact.HEIGHT_DP,
                    destinationBarVisible = destinationBarVisible,
                )
            viewportDataSource.followingPadding =
                EdgeInsets(
                    following.top * density,
                    following.left * density,
                    following.bottom * density,
                    following.right * density,
                )
            viewportDataSource.overviewPadding =
                EdgeInsets(
                    overview.top * density,
                    overview.left * density,
                    overview.bottom * density,
                    overview.right * density,
                )
        }
    }

    /**
     * Where this map is looking, for the host's `incidents.listNearby` poll, or
     * null before the camera can be read.
     *
     * Sampled by the screen on a slow timer rather than derived from the camera
     * flow: reading the visible bounds is a native call, and this camera changes
     * on essentially every frame while driving. The radius comes from the SAME
     * helper the shell surface uses ([MapboxMapSurface.visibleRadiusMeters]), so
     * both maps agree on what "covering the viewport" means.
     */
    fun queryViewport(): MapQueryViewport? =
        runCatching {
            val center = mapView.mapboxMap.cameraState.center
            val radius = MapboxMapSurface.visibleRadiusMeters(mapView.mapboxMap)
            radius?.let {
                MapQueryViewport(
                    latitude = center.latitude(),
                    longitude = center.longitude(),
                    radiusMeters = it,
                )
            }
        }.getOrNull()

    /**
     * The incident badges to draw on this map, pushed by the screen from the
     * host's shared list (empty when the "Traffic alerts" layer is off).
     */
    fun setIncidentMarkers(markers: List<MapIncidentMarker>) {
        incidentMarkers = markers
        applyIncidentMarkersIfChanged(markers)
    }

    /**
     * Redraws the badges only when the set actually differs, so the ~1 Hz
     * location ticks and the layer toggles that share this engine do not clear
     * and rebuild the whole layer for an unchanged list. Only a COMPLETE draw is
     * cached — an incomplete one (style handle not ready, so an icon could not be
     * uploaded) would otherwise be remembered as applied and leave those badges
     * blank until the set happened to change. Mirrors the shell surface's own
     * caching rule; the draw itself is the shared renderer.
     */
    private fun applyIncidentMarkersIfChanged(markers: List<MapIncidentMarker>) {
        if (markers == lastAppliedIncidents) return
        val manager = incidentMarkerManager ?: return
        val complete =
            IncidentMarkerLayer.draw(
                manager = manager,
                style = mapView.mapboxMap.style,
                context = context,
                markers = markers,
                registeredImages = registeredIncidentImages,
                idsByAnnotation = incidentIdsByAnnotation,
            )
        if (complete) lastAppliedIncidents = markers
    }

    /**
     * Disposes the incident annotation manager, if any, and clears everything
     * scoped to it.
     *
     * Same disposal contract as [releaseDestMarkerManager], and for the same
     * reason: in Maps SDK 11.26.0 an annotation manager cannot survive a style
     * reload — nothing re-adds its source and layer to the new style — so it is
     * retired before each load and rebuilt against the fresh one.
     * `removeAnnotationManager` (not just `deleteAll`) is what actually drops
     * the manager from the plugin's list and removes its layers, sources and map
     * interactions.
     *
     * The registered style-image names go with it: they were uploaded to the
     * style that is being replaced, so forgetting them is what makes the icons
     * come back after a day/night flip instead of referencing images the new
     * style has never heard of.
     */
    private fun releaseIncidentMarkerManager() {
        registeredIncidentImages.clear()
        incidentIdsByAnnotation.clear()
        lastAppliedIncidents = null
        val manager = incidentMarkerManager ?: return
        incidentMarkerManager = null
        runCatching { manager.deleteAll() }
        runCatching { mapView.annotations.removeAnnotationManager(manager) }
    }

    /**
     * (Re)add and (re)style the congestion layer on [style] from the remembered
     * toggle state. Anchored below the same label layer the route line is
     * anchored below, and added BEFORE the route line, so the route draws on top
     * of the traffic rather than under it.
     */
    private fun applyTrafficLayers(style: Style) {
        runCatching {
            MapboxMapSurface.addTrafficLayer(
                style = style,
                mode = trafficMode,
                // Classic navigation style: no slots to name.
                slotName = null,
                belowLayerId = ROUTE_LINE_BELOW_LAYER_ID,
            )
        }
        runCatching { MapboxMapSurface.applyTrafficColors(style, trafficMode) }
        runCatching { MapboxMapSurface.applyTrafficVisibility(style, trafficEnabled) }
    }

    private fun requestRoute(originPoint: Point) {
        val nav = mapboxNavigation ?: return
        if (routeRequested) return
        routeRequested = true
        routeRequestAttempts++
        val destinationPoint = Point.fromLngLat(destination.longitude, destination.latitude)
        // Wrapped defensively (see the class KDoc): a synchronous throw from the
        // native requestRoutes call would otherwise crash the screen AND leave
        // routeRequested stuck at true, blocking every future retry. Release the
        // in-flight guard on failure so the next location fix retries (bounded by
        // maxRouteRequestAttempts, already incremented above).
        runCatching {
            nav.requestRoutes(
                RouteOptions.builder()
                    .applyDefaultNavigationOptions()
                    .applyLanguageAndVoiceUnitOptions(context)
                    .coordinatesList(listOf(originPoint, destinationPoint))
                    .layersList(listOf(nav.getZLevel(), null))
                    .build(),
                object : NavigationRouterCallback {
                    override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                        // Mirror onFailure: free the in-flight guard so a cancelled
                        // request can be retried on the next location fix (bounded by
                        // maxRouteRequestAttempts) instead of leaving routeRequested
                        // stuck at true and blocking all further attempts.
                        routeRequested = false
                    }

                    override fun onFailure(
                        reasons: List<RouterFailure>,
                        routeOptions: RouteOptions,
                    ) {
                        // Free the in-flight guard so the next location fix retries
                        // (bounded by maxRouteRequestAttempts). Works for both the
                        // explicit-origin and current-location cases; routeRequestAttempts
                        // was already incremented when this attempt was issued.
                        routeRequested = false
                        reportRouteFailureIfExhausted()
                    }

                    override fun onRoutesReady(
                        routes: List<NavigationRoute>,
                        routerOrigin: String,
                    ) {
                        nav.setNavigationRoutes(routes)
                        navigationCamera.requestNavigationCameraToFollowing()
                    }
                },
            )
        }.onFailure {
            routeRequested = false
            reportRouteFailureIfExhausted()
        }
    }

    /**
     * Feature health: report a route request that has burned its whole retry
     * budget, which is the point at which turn-by-turn becomes a silent dead end
     * (a map, a following camera, and no route — with nothing thrown and nothing
     * shown).
     *
     * Gated on the retry budget on purpose: a single failed attempt is routine
     * (a momentary radio drop, a fix arriving before the network settles) and the
     * next location fix retries it, so reporting one would be a false positive.
     * Only exhaustion means the feature is actually broken for this user.
     *
     * The [RouterFailure] reasons are NOT reported — router messages can embed
     * the request URL, and that URL contains the origin and destination
     * coordinates. The issue is world-readable.
     */
    private fun reportRouteFailureIfExhausted() {
        if (routeRequestAttempts < maxRouteRequestAttempts) return
        health.report(
            kind = FeatureHealthKind.NavRouteRequestFailed,
            foreground = runCatching { isForeground() }.getOrDefault(false),
            surfaceShown = true,
        )
    }

    /** Release the render APIs; call from the composable's onDispose. */
    fun cancel() {
        runCatching { maneuverApi.cancel() }
        runCatching { routeLineApi.cancel() }
        runCatching { routeLineView.cancel() }
        // Detach the bearing listener: it is registered on the MapView's map,
        // which outlives this call only long enough to be destroyed, and an
        // un-detached listener holds this engine alive through it.
        cameraChangeListener?.let { listener ->
            runCatching { mapView.mapboxMap.removeOnCameraChangeListener(listener) }
        }
        cameraChangeListener = null
        // Same disposal as a style reload: deleting the annotations alone left
        // the manager registered with the annotation plugin and its map
        // interactions live.
        releaseDestMarkerManager()
        releaseIncidentMarkerManager()
    }
}
