package com.kungsbackacarcommunity.app.roadcameras

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.incidents.IncidentAgeOption
import com.kungsbackacarcommunity.app.shell.MapLayersPopup
import com.kungsbackacarcommunity.app.shell.MapLayersPreferenceStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h900dp-mdpi")
class RoadCameraLayersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disabledLayersDoNotFetchAndEnablingOnlyLoadsTheCatalog() {
        compose.activity.getSharedPreferences("map_layers_preference", Context.MODE_PRIVATE).edit().clear().commit()
        val catalogs = java.util.concurrent.atomic.AtomicInteger()
        val photos = java.util.concurrent.atomic.AtomicInteger()
        val repository = object : RoadCameraRepository {
            override suspend fun catalog(): RoadCameraCatalog {
                catalogs.incrementAndGet()
                return RoadCameraCatalog(emptyList(), null)
            }
            override suspend fun photo(cameraId: String): RoadCameraPhoto {
                photos.incrementAndGet()
                error("A catalog must not request a photo")
            }
        }
        lateinit var state: RoadCameraLayerState
        compose.setContent {
            state = rememberRoadCameraLayerState(compose.activity, true) { repository }
            androidx.compose.material3.Text(if (state.anyEnabled) "enabled" else "disabled")
        }
        compose.waitForIdle()
        assertEquals(0, catalogs.get())
        assertEquals(0, photos.get())
        compose.runOnIdle { state.setEnabled(RoadCameraType.Traffic, true) }
        compose.waitForIdle()
        assertEquals(1, catalogs.get())
        assertEquals(0, photos.get())
    }

    @Test fun cameraLayersAreIndependentPersistAndRenderThroughTheProductionPopup() {
        compose.activity.getSharedPreferences("map_layers_preference", Context.MODE_PRIVATE).edit().clear().commit()
        val state = RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), null)
        assertFalse(state.anyEnabled)
        state.cameras = RoadCameraType.entries.map { RoadCamera(it.wire, it, it.wire, 57.0, 12.0) }
        assertTrue(state.markers.isEmpty())
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(LocalRoadCameraLayers provides state) {
                    Box(Modifier.requiredSize(320.dp, 800.dp)) {
                        MapLayersPopup(incidentsOn = false, onIncidentsChange = {}, trafikverketDataShown = false,
                            trafficOn = false, is3d = false, onTrafficChange = {}, on3dChange = {},
                            browsingZoom = 12.0, onBrowsingZoomChange = {}, incidentMaxAge = IncidentAgeOption.ALL,
                            onIncidentMaxAgeChange = {}, crownHuntParticipating = false,
                            onCrownHuntParticipatingChange = {}, onDismiss = {})
                    }
                }
            }
        }
        RoadCameraType.entries.forEach { type ->
            compose.onNodeWithTag("map_layers_camera_${type.wire}").performScrollTo().performClick()
            assertTrue(MapLayersPreferenceStore(compose.activity).readRoadCameraLayer(type))
        }
        assertEquals(3, state.markers.size)
        compose.onNodeWithTag("map_layers_camera_traffic").performScrollTo().performClick()
        assertEquals(2, state.markers.size)
        assertNull(state.selected(ROAD_CAMERA_MARKER_PREFIX + "traffic"))
        assertTrue(state.speedEnabled && state.roadConditionEnabled)
    }
}
