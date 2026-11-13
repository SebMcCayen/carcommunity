package com.kungsbackacarcommunity.app.roadcameras

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.MapDetailSheet
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
    var trafficEnabled by mutableStateOf(store.readRoadCameraLayer(RoadCameraType.Traffic)); private set
    var roadConditionEnabled by mutableStateOf(store.readRoadCameraLayer(RoadCameraType.RoadCondition)); private set
    var cameras by mutableStateOf(emptyList<RoadCamera>()); internal set
    var catalogFailed by mutableStateOf(false); internal set
    val anyEnabled get() = speedEnabled || trafficEnabled || roadConditionEnabled
    fun enabled(type: RoadCameraType) = when (type) {
        RoadCameraType.Speed -> speedEnabled
        RoadCameraType.Traffic -> trafficEnabled
        RoadCameraType.RoadCondition -> roadConditionEnabled
    }
    fun setEnabled(type: RoadCameraType, enabled: Boolean) {
        when (type) {
            RoadCameraType.Speed -> speedEnabled = enabled
            RoadCameraType.Traffic -> trafficEnabled = enabled
            RoadCameraType.RoadCondition -> roadConditionEnabled = enabled
        }
        store.writeRoadCameraLayer(type, enabled)
    }
    /** Bound native annotation work; panning reuses the complete local catalog without a network read. */
    fun markersNear(latitude: Double?, longitude: Double?): List<MapIncidentMarker> {
        if (latitude == null || longitude == null) return emptyList()
        val longitudeScale = kotlin.math.cos(Math.toRadians(latitude))
        return markers.map { marker ->
            val north = (marker.latitude - latitude) * 111_000
            val east = (marker.longitude - longitude) * 111_000 * longitudeScale
            marker to (north * north + east * east)
        }.filter { it.second <= 50_000.0 * 50_000.0 }.sortedBy { it.second }.take(250).map { it.first }
    }
    val markers: List<MapIncidentMarker> get() = cameras.filter { enabled(it.type) }.map {
        MapIncidentMarker(ROAD_CAMERA_MARKER_PREFIX + it.id, it.longitude, it.latitude,
            cameraColor(it.type), cameraGlyph(it.type), cameraGlyphColor(it.type))
    }
    fun selected(markerId: String?): RoadCamera? {
        val id = markerId?.takeIf { it.startsWith(ROAD_CAMERA_MARKER_PREFIX) }?.removePrefix(ROAD_CAMERA_MARKER_PREFIX)
        return cameras.firstOrNull { it.id == id && enabled(it.type) }
    }
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
fun RoadCameraDetailsSheet(camera: RoadCamera, repository: RoadCameraRepository?, onDismiss: () -> Unit) {
    var photo by remember(camera.id) { mutableStateOf<RoadCameraPhoto?>(null) }
    var loading by remember(camera.id) { mutableStateOf(camera.type != RoadCameraType.Speed) }
    var limited by remember(camera.id) { mutableStateOf(false) }
    var failed by remember(camera.id) { mutableStateOf(false) }
    var retry by remember(camera.id) { mutableIntStateOf(0) }
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).testTag("road_camera_photo"),
                    contentScale = ContentScale.Fit)
                photo?.photoTime?.let { raw ->
                    val time = runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                        .withZone(ZoneId.systemDefault()).format(Instant.parse(raw)) }.getOrNull()
                    if (time != null) Text(stringResource(R.string.roadCameras_photoTime, time), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
