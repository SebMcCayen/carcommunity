package com.kungsbackacarcommunity.app.navigation.turnbyturn

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.MapboxOptions
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.location
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

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
 * - exposes a "Report incident/roadwork" affordance wired to [onReportIncident].
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
 * @param onReportIncident report an incident/roadwork; wired by the host to the
 *   incidents feature when present, else a "coming soon" no-op (see host).
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
@Composable
fun TurnByTurnNavScreen(
    origin: LatLng?,
    destination: LatLng,
    destinationLabel: String,
    onExit: () -> Unit,
    onReportIncident: () -> Unit,
    modifier: Modifier = Modifier,
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
    val navStyleUri =
        if (isSystemInDarkTheme()) {
            NavigationStyles.NAVIGATION_NIGHT_STYLE
        } else {
            NavigationStyles.NAVIGATION_DAY_STYLE
        }
    LaunchedEffect(engine, navStyleUri) {
        engine.loadStyleAndInit(navStyleUri)
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

    BackHandler { onExit() }

    Box(modifier = modifier.fillMaxSize().testTag(TURN_BY_TURN_TEST_TAG)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

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
        }

        // Right side: re-centre (only when the camera is NOT following) + report.
        Column(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(KccSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            FloatingActionButton(
                onClick = onReportIncident,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.turnByTurn_reportIncident),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (!following) {
                FloatingActionButton(
                    onClick = { engine.recenter() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = stringResource(R.string.turnByTurn_recenter),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        // Bottom: ETA / remaining progress bar with an explicit exit action.
        NavProgressBar(
            progress = progress,
            onExit = onExit,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
        runCatching {
            mapView.mapboxMap.loadStyle(styleUri) { style ->
                runCatching { routeLineView.initializeLayers(style) }
            }
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
    }
}
