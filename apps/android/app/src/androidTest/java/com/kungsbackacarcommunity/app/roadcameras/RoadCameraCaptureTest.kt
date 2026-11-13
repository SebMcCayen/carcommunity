package com.kungsbackacarcommunity.app.roadcameras

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.incidents.IncidentAgeOption
import com.kungsbackacarcommunity.app.shell.DockedMapSheetHost
import com.kungsbackacarcommunity.app.shell.IncidentMarkerBitmaps
import com.kungsbackacarcommunity.app.shell.MapLayersPopup
import com.kungsbackacarcommunity.app.shell.MapLayersPreferenceStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Local-only UI fixture using production marker bitmaps, layers popup and camera sheet. */
class RoadCameraCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureCameraComponents() {
        val args = InstrumentationRegistry.getArguments()
        val screen = args.getString("cameraSurface") ?: "markers"
        require(screen in setOf("markers", "layers", "photo"))
        val dark = args.getString("redesignTheme") != "light"
        val scale = args.getString("cameraFontScale")?.toFloat() ?: 1f
        require(scale in 1f..2f)
        val context = compose.activity
        context.getSharedPreferences("map_layers_preference", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val state = RoadCameraLayerState(MapLayersPreferenceStore(context), null)
        val photoCalls = AtomicInteger()
        val image = Bitmap.createBitmap(385, 290, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(android.graphics.Color.rgb(55, 65, 75))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; strokeWidth = 5f }
            canvas.drawLine(110f, 290f, 180f, 80f, paint)
            canvas.drawLine(275f, 290f, 205f, 80f, paint)
            paint.textSize = 23f
            canvas.drawText("LOKAL TESTBILD", 80f, 40f, paint)
        }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val source = object : RoadCameraRepository {
            override suspend fun catalog() = error("Capture must not read a catalog")
            override suspend fun photo(cameraId: String): RoadCameraPhoto {
                photoCalls.incrementAndGet(); delay(100)
                return RoadCameraPhoto(bytes, "image/jpeg", "2026-11-12T12:00:00Z")
            }
        }
        val cache = CachedRoadCameraRepository(File(context.cacheDir, "capture-road-camera-${System.nanoTime()}"), source)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalRoadCameraLayers provides state) {
                KccTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        DockedMapSheetHost {
                            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("DESIGNPREVIEW · LOKALA TESTDATA", style = MaterialTheme.typography.labelMedium)
                                if (screen == "markers") {
                                    Text("Kameramarkörer · befintlig kartrenderare", style = MaterialTheme.typography.titleMedium)
                                    RoadCameraType.entries.forEach { type ->
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            val bitmap = remember(type) { checkNotNull(IncidentMarkerBitmaps.create(context,
                                                cameraGlyph(type), cameraColor(type), cameraGlyphColor(type))) }
                                            Image(bitmap.asImageBitmap(), null,
                                                Modifier.size((bitmap.width / density).dp))
                                            Text(stringResource(cameraLabel(type)), style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                                if (screen == "photo") {
                                    var open by remember { mutableStateOf(false) }
                                    Button(onClick = { open = true }, modifier = Modifier.testTag("open_test_camera")) {
                                        Text("Öppna lokal testkamera")
                                    }
                                    if (open) RoadCameraDetailsSheet(
                                        RoadCamera("local-camera", RoadCameraType.Traffic, "Lokal testkamera · E6", 57.0, 12.0),
                                        cache, onDismiss = { open = false })
                                }
                            }
                            if (screen == "layers") MapLayersPopup(
                                incidentsOn = false, onIncidentsChange = {}, trafikverketDataShown = false,
                                trafficOn = false, is3d = false, onTrafficChange = {}, on3dChange = {},
                                browsingZoom = 12.0, onBrowsingZoomChange = {}, incidentMaxAge = IncidentAgeOption.ALL,
                                onIncidentMaxAgeChange = {}, crownHuntParticipating = false,
                                onCrownHuntParticipatingChange = {}, onDismiss = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, photoCalls.get())
        if (screen == "layers") {
            RoadCameraType.entries.forEach { type ->
                compose.onNodeWithTag("map_layers_camera_${type.wire}").performScrollTo().performClick()
            }
            compose.onNodeWithTag("map_layers_camera_speed").performScrollTo()
        }
        if (screen == "photo") {
            compose.onNodeWithTag("open_test_camera").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("road_camera_photo").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, photoCalls.get())
            compose.onNodeWithTag("road_camera_photo").assertIsDisplayed()
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(context.getExternalFilesDir(null), "road-cameras")
        check(output.mkdirs() || output.isDirectory)
        File(output, "$screen-${if (dark) "dark" else "light"}-$scale.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
