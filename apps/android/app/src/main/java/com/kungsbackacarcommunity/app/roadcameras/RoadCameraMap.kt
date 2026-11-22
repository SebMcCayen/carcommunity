package com.kungsbackacarcommunity.app.roadcameras

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.MapDetailSheet
import com.kungsbackacarcommunity.app.shell.MapViewportBounds
import com.kungsbackacarcommunity.app.shell.MapIncidentMarker
import com.kungsbackacarcommunity.app.shell.MapLayersPreferenceStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException

const val ROAD_CAMERA_MARKER_PREFIX = "road-camera:"
val LocalRoadCameraLayers = staticCompositionLocalOf<RoadCameraLayerState?> { null }

class RoadCameraLayerState(private val store: MapLayersPreferenceStore, val repository: RoadCameraRepository?) {
    var speedEnabled by mutableStateOf(store.readRoadCameraLayer(RoadCameraType.Speed)); private set
    var photoCamerasEnabled by mutableStateOf(store.readRoadCameraLayer(RoadCameraType.Traffic)); private set
    var cameras by mutableStateOf(emptyList<RoadCamera>()); internal set
    private val camerasById by derivedStateOf { cameras.associateBy { it.id } }
    private val locations by derivedStateOf { groupRoadCameraLocations(cameras) }
    private var displayedGroups: List<RoadCameraMapGroup> = emptyList()
    var catalogFailed by mutableStateOf(false); internal set
    val anyEnabled get() = speedEnabled || photoCamerasEnabled
    fun enabled(type: RoadCameraType) = when (type) {
        RoadCameraType.Speed -> speedEnabled
        RoadCameraType.Traffic, RoadCameraType.RoadCondition -> photoCamerasEnabled
    }
    fun setEnabled(type: RoadCameraType, enabled: Boolean) {
        when (type) {
            RoadCameraType.Speed -> speedEnabled = enabled
            RoadCameraType.Traffic, RoadCameraType.RoadCondition -> photoCamerasEnabled = enabled
        }
        store.writeRoadCameraLayer(type, enabled)
    }
    /** Bound native annotation work; panning reuses the complete local catalog without a network read. */
    fun markersNear(latitude: Double?, longitude: Double?, zoom: Double = 17.0, coordinatedDensity: Boolean = false,
        viewport: MapViewportBounds? = null,
    ): List<MapIncidentMarker> {
        if (latitude == null || longitude == null) return emptyList()
        val longitudeScale = kotlin.math.cos(Math.toRadians(latitude))
        fun distance(camera: RoadCamera): Double {
            val north = (camera.latitude - latitude) * 111_000
            val east = (camera.longitude - longitude) * 111_000 * longitudeScale
            return north * north + east * east
        }
        val nearby = locations.filter { site ->
            val camera = site.camera
            enabled(camera.type) && (viewport?.contains(camera.latitude, camera.longitude)
                ?: (distance(camera) <= 50_000.0 * 50_000.0))
        }
        // Group before the draw cap so counts include every nearby source camera.
        // Keep every site and its direction views available to that presentation.
        val grouped = if (coordinatedDensity) nearby.map { RoadCameraMapGroup(listOf(it)) } else groupRoadCameraMarkers(nearby, zoom)
        displayedGroups = if (coordinatedDensity) grouped else grouped.sortedWith(
            compareBy<RoadCameraMapGroup> { distance(it.camera) }.thenBy { it.camera.id },
        ).take(250)
        return displayedGroups.map { group ->
            val camera = group.camera
            MapIncidentMarker(ROAD_CAMERA_MARKER_PREFIX + camera.id, camera.longitude, camera.latitude,
                cameraColor(camera.type), cameraGlyph(camera.type), cameraGlyphColor(camera.type),
                groupCount = group.views.size, opposingDirections = group.opposingDirections)
        }
    }
    val markers: List<MapIncidentMarker> get() = locations.map { it.camera }.filter { enabled(it.type) }.map {
        MapIncidentMarker(ROAD_CAMERA_MARKER_PREFIX + it.id, it.longitude, it.latitude,
            cameraColor(it.type), cameraGlyph(it.type), cameraGlyphColor(it.type))
    }
    fun viewsFor(camera: RoadCamera): List<RoadCamera> =
        displayedGroups.firstOrNull { it.camera.id == camera.id }?.views
            ?: locations.firstOrNull { location -> location.views.any { it.id == camera.id } }?.views ?: listOf(camera)

    fun selected(markerId: String?): RoadCamera? {
        val id = markerId?.takeIf { it.startsWith(ROAD_CAMERA_MARKER_PREFIX) }?.removePrefix(ROAD_CAMERA_MARKER_PREFIX)
        return camerasById[id]?.takeIf { enabled(it.type) }
    }
}

/** Keep an open selection stable across viewport regrouping, while honoring catalog removals. */
@Composable
fun rememberRoadCameraSelectionViews(state: RoadCameraLayerState, camera: RoadCamera): List<RoadCamera> {
    val memberIds = remember(state, camera.id) { state.viewsFor(camera).map { it.id } }
    val catalog = remember(state.cameras) { state.cameras.associateBy { it.id } }
    return memberIds.mapNotNull { id -> catalog[id]?.takeIf { state.enabled(it.type) } }
}

/** Enabling a layer is the only catalog trigger; map movement never calls the service. */
@Composable
fun rememberRoadCameraLayerState(context: Context, mapVisible: Boolean,
    repositoryFactory: () -> RoadCameraRepository? = { FirebaseRoadCameraRepository.createIfAvailable(context) },
): RoadCameraLayerState {
    val state = remember(context) {
        RoadCameraLayerState(MapLayersPreferenceStore(context), repositoryFactory())
    }
    LaunchedEffect(state.anyEnabled, mapVisible, state.repository) {
        if (!state.anyEnabled || !mapVisible) return@LaunchedEffect
        try {
            state.repository?.catalog()?.let { state.cameras = it.cameras }
            state.catalogFailed = false
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            state.catalogFailed = true
        }
    }
    return state
}

@StringRes fun cameraLabel(type: RoadCameraType) = when (type) {
    RoadCameraType.Speed -> R.string.roadCameras_speedLayer
    RoadCameraType.Traffic -> R.string.roadCameras_trafficLayer
    RoadCameraType.RoadCondition -> R.string.roadCameras_roadConditionLayer
}
@DrawableRes fun cameraGlyph(type: RoadCameraType) = when (type) {
    RoadCameraType.Speed -> R.drawable.ic_camera_speed
    RoadCameraType.Traffic -> R.drawable.ic_camera_traffic
    RoadCameraType.RoadCondition -> R.drawable.ic_camera_road_condition
}
// The established map palette: crown gold, driver teal and overlay slate.
fun cameraColor(type: RoadCameraType) = when (type) {
    RoadCameraType.Speed -> 0xFFEAB54B.toInt()
    RoadCameraType.Traffic -> 0xFF007582.toInt()
    RoadCameraType.RoadCondition -> 0xFF526172.toInt()
}
fun cameraGlyphColor(type: RoadCameraType) = if (type == RoadCameraType.Speed) 0xFF040211.toInt() else 0xFFF8F6EF.toInt()

@Composable
fun RoadCameraBadge(type: RoadCameraType) {
    Box(Modifier.size(36.dp).background(Color(cameraColor(type)), CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(cameraGlyph(type)), null, Modifier.size(24.dp), tint = Color(cameraGlyphColor(type)))
    }
}

@Composable
fun RoadCameraDetailsSheet(camera: RoadCamera, repository: RoadCameraRepository?,
    views: List<RoadCamera> = listOf(camera), onZoomIn: (() -> Unit)? = null, onDismiss: () -> Unit,
) {
    var selectedId by remember(camera.id) { mutableStateOf(camera.id) }
    val selected = views.firstOrNull { it.id == selectedId } ?: camera
    RoadCameraViewDetailsSheet(selected, repository, views, { selectedId = it.id }, onZoomIn, onDismiss)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoadCameraViewDetailsSheet(camera: RoadCamera, repository: RoadCameraRepository?,
    views: List<RoadCamera>, onSelect: (RoadCamera) -> Unit, onZoomIn: (() -> Unit)?, onDismiss: () -> Unit,
) {
    var photo by remember(camera.id) { mutableStateOf<RoadCameraPhoto?>(null) }
    var loading by remember(camera.id) { mutableStateOf(camera.type != RoadCameraType.Speed) }
    var limited by remember(camera.id) { mutableStateOf(false) }
    var failed by remember(camera.id) { mutableStateOf(false) }
    var retry by remember(camera.id) { mutableIntStateOf(0) }
    var photoExpanded by remember(camera.id) { mutableStateOf(false) }
    LaunchedEffect(camera.id, retry) {
        if (camera.type == RoadCameraType.Speed) return@LaunchedEffect
        loading = true; failed = false; limited = false
        try {
            photo = requireNotNull(repository).photo(camera.id)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            limited = error is RoadCameraRateLimited
            failed = true
        } finally { loading = false }
    }
    val bitmap = remember(photo) {
        photo?.let { image ->
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
                BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    MapDetailSheet(title = stringResource(cameraLabel(camera.type)), onDismiss = onDismiss,
        leadingContent = { RoadCameraBadge(camera.type) }, modifier = Modifier.testTag("road_camera_details")) {
        Text(camera.name, style = MaterialTheme.typography.titleMedium)
        if (views.size > 1) {
            Text(stringResource(R.string.roadCameras_groupCount, views.size), style = MaterialTheme.typography.titleSmall)
            if (views.size == 2 && camera.type == RoadCameraType.Speed && groupRoadCameraLocations(views).size == 1) {
                Text(stringResource(R.string.roadCameras_bothDirections))
            }
            Text(stringResource(R.string.roadCameras_zoomHint), style = MaterialTheme.typography.bodySmall)
            if (onZoomIn != null && groupRoadCameraLocations(views).size > 1) {
                TextButton(onClick = onZoomIn, modifier = Modifier.testTag("road_camera_zoom_group")) {
                    Text(stringResource(R.string.roadCameras_showCameras))
                }
            }
            Text(stringResource(R.string.roadCameras_chooseDirection), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                views.forEachIndexed { index, view ->
                    FilterChip(selected = camera.id == view.id, onClick = { if (camera.id != view.id) onSelect(view) },
                        label = {
                            val label = cameraViewLabel(view, views, index)
                            Text(if (views.any { it.name != view.name }) "${view.name} · $label" else label)
                        },
                        modifier = Modifier.testTag("road_camera_direction_${view.id}"))
                }
            }
        }
        Text(stringResource(R.string.incidents_sourceTrafikverket), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (camera.type != RoadCameraType.Speed) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(stringResource(R.string.roadCameras_loading))
            } else if (failed || bitmap == null) {
                Text(stringResource(if (limited) R.string.roadCameras_rateLimited else R.string.roadCameras_unavailable))
                TextButton(onClick = { retry++ }, enabled = !limited) { Text(stringResource(R.string.roadCameras_retry)) }
            } else {
                Image(bitmap, contentDescription = camera.name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                        .clickable(onClickLabel = stringResource(R.string.roadCameras_expandPhoto), role = Role.Button) {
                            photoExpanded = true
                        }.testTag("road_camera_photo"),
                    contentScale = ContentScale.Fit)
                Text(stringResource(R.string.roadCameras_expandPhotoHint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                photo?.photoTime?.let { raw ->
                    val time = runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                        .withZone(ZoneId.systemDefault()).format(Instant.parse(raw)) }.getOrNull()
                    if (time != null) Text(stringResource(R.string.roadCameras_photoTime, time), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (photoExpanded && bitmap != null && !loading && !failed) {
        RoadCameraPhotoDialog(camera.name, bitmap, onDismiss = { photoExpanded = false })
    }
}

@Composable
private fun RoadCameraPhotoDialog(name: String, bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("road_camera_photo_viewer")) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("road_camera_photo_close")) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.roadCameras_closePhoto))
                    }
                }
                Image(bitmap, contentDescription = name,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("road_camera_photo_expanded"),
                    contentScale = ContentScale.Fit)
                Text(stringResource(R.string.incidents_sourceTrafikverket),
                    modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun cameraViewLabel(camera: RoadCamera, views: List<RoadCamera>, index: Int): String {
    val compass = camera.compassIndex() ?: return stringResource(R.string.roadCameras_numberedView, index + 1)
    val key = when (compass) {
        0 -> R.string.roadCameras_north
        1 -> R.string.roadCameras_northEast
        2 -> R.string.roadCameras_east
        3 -> R.string.roadCameras_southEast
        4 -> R.string.roadCameras_south
        5 -> R.string.roadCameras_southWest
        6 -> R.string.roadCameras_west
        else -> R.string.roadCameras_northWest
    }
    val label = stringResource(key)
    return if (views.count { it.compassIndex() == compass } > 1)
        stringResource(R.string.roadCameras_numberedDirection, label, index + 1) else label
}
