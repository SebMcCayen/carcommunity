package com.kungsbackacarcommunity.app.navigation.turnbyturn

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
import com.kungsbackacarcommunity.app.design.LocalKccStatusColors
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.incidents.IncidentTypePickerDialog
import com.kungsbackacarcommunity.app.map.MapMarkerStyle
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.shell.CircleControl
import com.kungsbackacarcommunity.app.shell.LiveSharePopup
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.MapboxOptions
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.observable.eventdata.CameraChangedEventData
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.delegates.listeners.OnCameraChangeListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.scalebar.scalebar
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
import com.mapbox.navigation.ui.components.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.maps.NavigationStyles
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraTransitionOptions
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
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
 * Full-screen, Google-Maps-style turn-by-turn navigation view backed by the
 * Mapbox Navigation SDK v3 (aligned with Maps SDK v11.26 — see the `mapboxNav`
 * version catalog entry).
 *
 * Entered from the address-search route preview's "Start" button. It:
 * - follows the user with a tilted, auto-following [NavigationCamera] that
 *   continuously re-frames the route ahead as the location changes,
 * - shows a maneuver banner ([MapboxManeuverView]) with the CURRENT turn and the
 *   upcoming maneuver(s) after it,
 * - lets the user pinch-zoom/pan freely (a [NavigationBasicGesturesHandler]
 *   detaches auto-follow on interaction) and offers a "Re-centre" button that
 *   snaps the camera back to following,
 * - exposes a "Report incident/roadwork" affordance wired to [onReportIncident],
 * - shows the driver's current speed, and the POSTED legal limit for the road
 *   whenever the SDK has one (see [NavSpeedInfo]),
 * - keeps the map's compass and live-location controls reachable while driving.
 *
 * ## Chrome parity with the map home
 * The bottom-right control stack is deliberately built from the map home's
 * controls ([CircleControl], same glyphs, same shape and colour rules):
 * navigation is a MODE of the map, not a different app, so the compass and
 * live-location buttons must not change icon or treatment when the user presses
 * "Start".
 *
 * The parity is of control LANGUAGE, not of stack order or tap behaviour, and
 * the two diverge on purpose. This screen's camera is a FOLLOW camera: the
 * my-location control only exists once follow has detached, and re-centring
 * means resuming follow rather than a one-off camera move. So the compass here
 * resets bearing only (the engine's `resetNorth`), while the map home's compass
 * also re-centres — folding a re-centre in here would silently re-arm follow
 * from a control that does not say so. The map home's stack likewise leads with
 * its report control; this one keeps the compass on top, where a driver's thumb
 * expects it. Neither is required to track the other.
 *
 * The Mapbox SDK's own scale bar
 * (upper-left) and compass (upper-right) are switched OFF here for the same
 * reason [com.kungsbackacarcommunity.app.shell.MapboxMapSurface] switches them
 * off: they are a second, differently-styled set of the same affordances.
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
 *   file anything.
 * @param isLiveSharing whether a live-location session is currently running.
 *   Turns the live control GREEN, exactly as on the map home. Starting to
 *   navigate does NOT stop a session, so this control has to stay on screen: the
 *   driver must be able to see that they are still broadcasting, and stop it,
 *   without leaving navigation first.
 * @param canShareLive whether the caller may START a session (the LIVE_LOCATION
 *   flag); forwarded straight to the shared [LiveSharePopup], which owns the
 *   gating.
 * @param onStartLiveShare start a solo live-sharing session (host raises the
 *   same single-session start flow the map home does).
 * @param onHideMeNow the privacy stop — remove my position now.
 * @param onOpenLiveShareDetails open the full live-location screen (the complete
 *   controls, including exactly who can see the caller, and the privacy details).
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
    onReportIncident: (IncidentType) -> Unit,
    modifier: Modifier = Modifier,
    incidentReportingEnabled: Boolean = false,
    // Defaulted so callers/tests that don't wire live sharing still compile; the
    // control then simply offers the (gated) start path.
    isLiveSharing: Boolean = false,
    canShareLive: Boolean = false,
    onStartLiveShare: () -> Unit = {},
    onHideMeNow: () -> Unit = {},
    onOpenLiveShareDetails: () -> Unit = {},
    convoyBar: (@Composable () -> Unit)? = null,
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
    val navStyleUri =
        if (LocalKccDarkTheme.current) {
            NavigationStyles.NAVIGATION_NIGHT_STYLE
        } else {
            NavigationStyles.NAVIGATION_DAY_STYLE
        }
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
    LaunchedEffect(engine, navStyleUri, phase.mapMounted) {
        if (phase.mapMounted) engine.loadStyleAndInit(navStyleUri)
    }

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

    val following by engine.cameraFollowing.collectAsState()
    val progress by engine.progress.collectAsState()
    val rerouting by engine.rerouting.collectAsState()
    val bearing by engine.bearing.collectAsState()
    val speed by engine.speed.collectAsState()

    // Live-location sheet open/close is local UI state, same as on the map home:
    // tapping the live control opens the shared transparent popup over the map.
    var liveOpen by remember { mutableStateOf(false) }

    // Incident-report category picker open/close — local, transient UI state, the
    // same shape as the map home's `reportOpen` (a plain `remember`, deliberately
    // NOT saveable: a half-made report should not survive process death).
    var reportOpen by remember { mutableStateOf(false) }

    BackHandler { onExit() }

    Box(modifier = modifier.fillMaxSize().testTag(TURN_BY_TURN_TEST_TAG)) {
        // Mounted only once the veil is opaque (see [NavHandoffPhase.mapMounted]).
        // Mounting it during the fade-in is precisely what let the blank GL
        // frames show through, because a MapView is SurfaceView-backed and is
        // punched through the window rather than composited with the veil above
        // it — it cannot be hidden by alpha, only by not being there yet.
        if (phase.mapMounted) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        }

        // Top: exit bar + maneuver banner (current turn + upcoming).
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(KccSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Surface(
                shape = RoundedCornerShape(KccRadius.full),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // The exit bar is the top-of-screen back affordance: tapping it
                    // (arrow or label) leaves navigation, same as the back gesture
                    // and the bottom "Exit" button. Role.Button + the exit label
                    // make it an announced, activatable control for a11y.
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
                // Bottom-RIGHT: the map home's controls and glyphs, in this
                // screen's own driving order — compass on top, live-location
                // under it. See the file KDoc on why the order and the compass's
                // tap behaviour deliberately do NOT track the map home's.
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                ) {
                    // Compass — identical control and glyph to the map home's
                    // (Icons.Filled.Navigation rotated by the live map bearing).
                    // Bearing reset ONLY here: re-centring is the follow camera's
                    // job and belongs to the re-centre button below. The SDK's own
                    // top-right compass is disabled so this is the only one on
                    // screen.
                    CircleControl(
                        icon = Icons.Filled.Navigation,
                        contentDescription = stringResource(R.string.shell_compass),
                        onClick = { engine.resetNorth() },
                        iconRotationDegrees = -bearing,
                    )
                    // Live-location — reachable WHILE driving. A session started
                    // before "Start" keeps running, so hiding this control (as it
                    // used to be hidden) left the driver broadcasting with no way
                    // to see or stop it without exiting navigation first.
                    CircleControl(
                        icon = Icons.Filled.Podcasts,
                        contentDescription =
                            stringResource(
                                if (isLiveSharing) {
                                    R.string.shell_liveShareOn
                                } else {
                                    R.string.shell_liveShareOff
                                },
                            ),
                        containerColor =
                            if (isLiveSharing) {
                                LocalKccStatusColors.current.success
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        contentColor =
                            if (isLiveSharing) Color.White else MaterialTheme.colorScheme.onSurface,
                        onClick = { liveOpen = true },
                    )
                    // Report incident/roadwork — the map home's control, not a
                    // navigation-specific one. It was a 56.dp tinted
                    // FloatingActionButton, which read as a different KIND of
                    // affordance sitting in a stack of 48.dp neutral circles; it
                    // is now the same [CircleControl] with the same neutral
                    // surface/onSurface treatment PR #468 established for the map
                    // home's report button, so the stack is one family of
                    // controls. Opens the SHARED category picker, whose choice
                    // goes to the host's single reporting path.
                    if (incidentReportingEnabled) {
                        CircleControl(
                            icon = Icons.Filled.Warning,
                            contentDescription =
                                stringResource(R.string.incidents_reportButton),
                            onClick = { reportOpen = true },
                            modifier = Modifier.testTag(TURN_BY_TURN_REPORT_TAG),
                        )
                    }
                    if (!following) {
                        FloatingActionButton(
                            onClick = { engine.recenter() },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MyLocation,
                                contentDescription =
                                    stringResource(R.string.turnByTurn_recenter),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            // Bottom: ETA / remaining progress bar with an explicit exit action.
            NavProgressBar(progress = progress, onExit = onExit)
        }

        // The SHARED live-location sheet from the map home — same wording, same
        // gating, same "More options" route into the full screen (where the
        // caller sees and controls exactly who can see them). Deliberately not a
        // second live-sharing UI.
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

        // The SHARED incident category picker from the map home — the same
        // composable, so navigation offers the same categories in the same order
        // with the same wording. Picking a category hands the choice straight to
        // the host, which routes it to the one `incidents-report` callable.
        if (reportOpen) {
            IncidentTypePickerDialog(
                onPick = { type ->
                    reportOpen = false
                    onReportIncident(type)
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
) {
    /** True while the camera auto-follows the user (hides the re-centre button). */
    private val cameraFollowingFlow = MutableStateFlow(true)
    val cameraFollowing: StateFlow<Boolean> = cameraFollowingFlow.asStateFlow()

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

    private val routeLineApi = MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build())
    private val routeLineView =
        MapboxRouteLineView(
            MapboxRouteLineViewOptions.Builder(context)
                .routeLineBelowLayerId("road-label-navigation")
                .build(),
        )
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

    private var firstFixReceived = false
    private var routeRequested = false

    // Bounded route-request retries. A failed request resets routeRequested to
    // false; the next location fix retries (with the explicit origin if we have
    // one, else the current fix). We cap total attempts so a persistently failing
    // route doesn't spam the routing service on every ~1 Hz location tick.
    private var routeRequestAttempts = 0
    private val maxRouteRequestAttempts = 3

    init {
        val density = mapView.resources.displayMetrics.density
        // Extra bottom room for the progress bar; top room for the maneuver banner.
        viewportDataSource.followingPadding =
            EdgeInsets(180.0 * density, 40.0 * density, 160.0 * density, 40.0 * density)
        viewportDataSource.overviewPadding =
            EdgeInsets(140.0 * density, 40.0 * density, 160.0 * density, 40.0 * density)

        // Detach auto-follow when the user pans/zooms; the state observer then
        // reveals the re-centre button.
        mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(navigationCamera),
        )
        navigationCamera.registerNavigationCameraStateChangeObserver { state ->
            cameraFollowingFlow.value =
                state == NavigationCameraState.FOLLOWING ||
                state == NavigationCameraState.TRANSITION_TO_FOLLOWING
        }

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
                    bearingFlow.value =
                        mapView.mapboxMap.cameraState.bearing.toFloat().roundToInt().toFloat()
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
     * the app theme) and initialises the route-line layers.
     */
    fun loadStyleAndInit(styleUri: String) {
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
        // Claim this request. Anything still in flight from an earlier one is
        // now stale and must not be allowed to end the handoff.
        val token = ++styleLoadToken
        val loaded =
            runCatching {
                mapView.mapboxMap.loadStyle(styleUri) { style ->
                    runCatching { routeLineView.initializeLayers(style) }
                    // Create the destination-marker manager against the freshly
                    // loaded style and redraw the marker. A style reload (day/night
                    // flip) drops every annotation, so the marker has to be redrawn
                    // here rather than only once at startup.
                    runCatching {
                        destMarkerManager = mapView.annotations.createCircleAnnotationManager()
                        drawDestinationMarker()
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
     * Ease the map back to north-up — the compass control's tap action, matching
     * [com.kungsbackacarcommunity.app.shell.MapboxMapSurface.resetNorth] so the
     * same button does the same thing on both screens.
     *
     * Note the deliberate asymmetry with the map home: while the navigation
     * camera is FOLLOWING it owns the bearing (the map is course-up), so it will
     * swing back on the next location update. The tap is still the right
     * behaviour — after the user has panned away, auto-follow is detached and
     * north-up sticks, which is exactly when someone reaches for a compass.
     */
    fun resetNorth() {
        runCatching {
            mapView.camera.easeTo(cameraOptions { bearing(0.0) })
        }
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
    }
}
