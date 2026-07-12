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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
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
    val engine =
        remember(mapView, maneuverView) {
            TurnByTurnEngine(mapView, maneuverView, origin, destination, context)
        }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(engine, lifecycleOwner) {
        engine.loadStyleAndInit()
        if (!MapboxNavigationApp.isSetup()) {
            MapboxNavigationApp.setup(NavigationOptions.Builder(context).build())
        }
        MapboxNavigationApp.attach(lifecycleOwner)
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
        MapboxNavigationApp.registerObserver(observer)
        onDispose {
            // Order matters: detach the app FIRST so this lifecycle owner leaving
            // fires the observer's onDetached → TurnByTurnEngine.detach() (which
            // unregisters the route/location/progress observers AND stops the trip
            // session). Unregistering the observer before detaching would suppress
            // onDetached and leak those observers + the running trip session.
            MapboxNavigationApp.detach(lifecycleOwner)
            MapboxNavigationApp.unregisterObserver(observer)
            engine.cancel()
            mapView.onDestroy()
        }
    }

    val following by engine.cameraFollowing.collectAsState()
    val progress by engine.progress.collectAsState()

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
                            NavProgressFormat.remaining(progress, unitM, unitKm, unitMin, unitH),
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
            Button(onClick = onExit, modifier = Modifier.padding(top = 8.dp)) {
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
) {
    /** True while the camera auto-follows the user (hides the re-centre button). */
    private val cameraFollowingFlow = MutableStateFlow(true)
    val cameraFollowing: StateFlow<Boolean> = cameraFollowingFlow.asStateFlow()

    /** Latest progress snapshot for the bottom ETA bar (null until the first tick). */
    private val progressFlow = MutableStateFlow<NavProgress?>(null)
    val progress: StateFlow<NavProgress?> = progressFlow.asStateFlow()

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

    /** Loads the navigation-day style and initialises the route-line layers. */
    fun loadStyleAndInit() {
        runCatching {
            mapView.mapboxMap.loadStyle(NavigationStyles.NAVIGATION_DAY_STYLE) { style ->
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
                maneuvers.fold({ /* ignore maneuver errors */ }, {
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

    private var mapboxNavigation: MapboxNavigation? = null

    @SuppressLint("MissingPermission")
    fun attach(mapboxNavigation: MapboxNavigation) {
        this.mapboxNavigation = mapboxNavigation
        mapboxNavigation.registerRoutesObserver(routesObserver)
        mapboxNavigation.registerLocationObserver(locationObserver)
        mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
        // Real GPS trip session (permission is guarded by the caller).
        mapboxNavigation.startTripSession()
        // If we already have an origin, request the route immediately.
        origin?.let { requestRoute(Point.fromLngLat(it.longitude, it.latitude)) }
    }

    fun detach(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterRoutesObserver(routesObserver)
        mapboxNavigation.unregisterLocationObserver(locationObserver)
        mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
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
    }

    /** Release the render APIs; call from the composable's onDispose. */
    fun cancel() {
        runCatching { maneuverApi.cancel() }
        runCatching { routeLineApi.cancel() }
        runCatching { routeLineView.cancel() }
    }
}
