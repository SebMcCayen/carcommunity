package com.kungsbackacarcommunity.app.partners

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccButton as Button
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.fillLayer
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import kotlinx.coroutines.delay
import java.util.Locale

internal val partnerHeatColors = listOf(0xfffee5d9, 0xfffcae91, 0xfffb6a4a, 0xffde2d26, 0xffa50f15)
private val heatLabels = listOf(R.string.partnerTraffic_band1, R.string.partnerTraffic_band2, R.string.partnerTraffic_band3, R.string.partnerTraffic_band4, R.string.partnerTraffic_band5)

@Composable
internal fun PartnerTrafficHeatScreen(repository: PartnerPortalRepository, companyId: String, onBack: () -> Unit, mapEnabled: Boolean = true) {
    BackHandler(onBack = onBack)
    val state = remember(repository, companyId) { PartnerTrafficHeatState() }
    val owner = LocalLifecycleOwner.current
    var active by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var account by remember(repository) { mutableStateOf(repository.trafficSessionKey) }
    var refreshRequest by remember(repository, companyId) { mutableIntStateOf(0) }
    DisposableEffect(owner, repository, state) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { state.clear(); active = false }
            if (event == Lifecycle.Event.ON_START) active = true
        }
        owner.lifecycle.addObserver(listener)
        val session = repository.observeTrafficSession { key -> if (key != account) { state.clear(); account = key } }
        onDispose { session.close(); owner.lifecycle.removeObserver(listener); state.clear() }
    }
    LaunchedEffect(repository, companyId, active, account, refreshRequest) {
        if (!active || account == null) { state.clear(); return@LaunchedEffect }
        while (true) {
            state.load(repository, companyId)
            if (state.error != null) break
            delay(60_000)
        }
    }
    // Expiry is independent of the request: a stalled reauthorization cannot extend a lease.
    LaunchedEffect(state, active, account) {
        if (active && account != null) while (true) { delay(1000); state.expireIfNeeded() }
    }
    AeroPage(title = stringResource(R.string.partnerTraffic_title), ownerShortcutEnabled = false, compactTitleWithBack = true) {
        Text(stringResource(R.string.partnerTraffic_purpose))
        Text(stringResource(R.string.partnerTraffic_privacy))
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        if (account == null) Text(stringResource(R.string.partnerPortal_accessDenied))
        if (state.expired) Text(stringResource(R.string.partnerTraffic_expired))
        if (state.error != null || state.expired) Button(onClick = { refreshRequest++ }, enabled = active && account != null && !state.busy) { Text(stringResource(R.string.partnerPortal_retry)) }
        state.snapshot?.let { data ->
            if (data.cells.isEmpty()) Text(stringResource(R.string.partnerTraffic_empty)) else {
                Text(stringResource(R.string.partnerTraffic_updated, portalDateTime(data.generatedAt!!)))
                if (data.truncated) Text(stringResource(R.string.partnerTraffic_limited))
                PartnerTrafficMap(data, mapEnabled)
                heatLabels.forEachIndexed { i, label ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(20.dp).background(Color(partnerHeatColors[i])))
                        Text(stringResource(label))
                    }
                }
                Text(stringResource(R.string.partnerTraffic_areas), style = MaterialTheme.typography.titleMedium)
                data.cells.take(20).forEach { cell ->
                    Text(stringResource(R.string.partnerTraffic_area, String.format(Locale.ROOT, "%.3f, %.3f", cell.latitude, cell.longitude), stringResource(heatLabels[cell.band - 1])))
                }
            }
        }
    }
}

@Composable
private fun PartnerTrafficMap(data: PartnerTrafficHeatSnapshot, enabled: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val token = context.getString(R.string.mapbox_access_token)
    val description = stringResource(R.string.partnerTraffic_map)
    var failed by remember(data.generatedAt, data.cells) { mutableStateOf(false) }
    if (!enabled || token.isBlank() || failed) { Text(stringResource(R.string.partnerTraffic_mapUnavailable)); return }
    key(data.generatedAt, data.cells) {
        val handle = remember { PartnerHeatMapHandle() }
        var styleReady by remember { mutableStateOf(false) }
        // A missing SDK callback must not leave an indefinitely blank map.
        LaunchedEffect(handle) { delay(20_000); if (!styleReady) failed = true }
        DisposableEffect(handle) { onDispose { handle.close() } }
        AndroidView(modifier = Modifier.fillMaxWidth().height(340.dp).semantics { contentDescription = description }, factory = { ctx ->
            MapboxOptions.accessToken = token
            MapView(ctx).apply {
                handle.view = this
                handle.loadingError = mapboxMap.subscribeMapLoadingError {
                    // Never log the SDK error: it can contain URLs or tile coordinates.
                    post { if (!handle.closed) failed = true }
                }
                val first = data.cells.first()
                mapboxMap.setCamera(cameraOptions { center(Point.fromLngLat(first.longitude, first.latitude.coerceIn(-85.0, 85.0))); zoom(12.0) })
                setOnTouchListener { view, event ->
                    view.parent?.requestDisallowInterceptTouchEvent(event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL)
                    false
                }
                mapboxMap.loadStyle(Style.STANDARD) { style ->
                    if (handle.closed) return@loadStyle
                    try {
                        for (band in 1..5) {
                            val features = data.cells.filter { it.band == band }.map { cell ->
                                val ring = cell.boundary.map { Point.fromLngLat(it.longitude, it.latitude) }
                                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring + ring.first())))
                            }
                            val source = "partner-heat-$band"
                            style.addSource(geoJsonSource(source) { featureCollection(FeatureCollection.fromFeatures(features)) })
                            style.addLayer(fillLayer("$source-fill", source) { slot("middle"); fillColor(Color(partnerHeatColors[band - 1]).toArgb()); fillOpacity(0.7) })
                        }
                        if (!handle.closed) styleReady = true
                    } catch (_: Exception) { if (!handle.closed) failed = true }
                }
            }
        }, onRelease = { handle.close() })
    }
}

/** Own every SDK subscription and release it when the map or protected snapshot leaves composition. */
private class PartnerHeatMapHandle {
    var view: MapView? = null
    var loadingError: Cancelable? = null
    var closed = false; private set
    fun close() {
        if (closed) return
        closed = true
        loadingError?.cancel(); loadingError = null
        view?.setOnTouchListener(null)
        view?.let { runCatching { it.onDestroy() } }; view = null
    }
}
