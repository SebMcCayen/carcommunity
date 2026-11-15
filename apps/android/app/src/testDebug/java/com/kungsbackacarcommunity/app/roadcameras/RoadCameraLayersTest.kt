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

    @Test fun zoomingGroupsBeforeTheDrawCapAndKeepsEveryDirectionSelectable() {
        compose.activity.getSharedPreferences("map_layers_preference", Context.MODE_PRIVATE).edit().clear().commit()
        val state = RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), null)
        state.cameras = (0..299).map { i ->
            RoadCamera("camera-$i", RoadCameraType.Speed, "Site $i", 57.45 + i * 0.000001, 12.09, 135.0)
        }
        state.setEnabled(RoadCameraType.Speed, true)
        val groups = state.markersNear(57.45, 12.09, 13.0)
        assertEquals(1, groups.size)
        assertEquals(300, groups.single().groupCount)
        assertFalse(groups.single().opposingDirections)
        assertEquals(300, state.viewsFor(state.selected(groups.single().id)!!).size)
        assertEquals(250, state.markersNear(57.45, 12.09, 17.0).size)
        state.setEnabled(RoadCameraType.Speed, false)
        assertTrue(state.markersNear(57.45, 12.09, 13.0).isEmpty())
        assertNull(state.selected(groups.single().id))
    }

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

    @Test fun eitherLegacyPhotoLayerEnablesBothTypesAndCombinedChoiceSurvivesRestart() {
        val prefs = compose.activity.getSharedPreferences("map_layers_preference", Context.MODE_PRIVATE)
        for (traffic in listOf(false, true)) for (roadCondition in listOf(false, true)) {
            prefs.edit().clear().putBoolean("camera_traffic", traffic)
                .putBoolean("camera_road_condition", roadCondition).commit()
            val state = RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), null)
            state.cameras = RoadCameraType.entries.map { RoadCamera(it.wire, it, it.wire, 57.0, 12.0) }
            assertEquals(traffic || roadCondition, state.enabled(RoadCameraType.Traffic))
            assertEquals(traffic || roadCondition, state.enabled(RoadCameraType.RoadCondition))
            assertEquals(if (traffic || roadCondition) 2 else 0, state.markers.size)
            assertFalse(state.speedEnabled)
            state.setEnabled(RoadCameraType.RoadCondition, true)
            assertTrue(RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), null).photoCamerasEnabled)
            state.setEnabled(RoadCameraType.Traffic, false)
            val restored = RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), null)
            assertFalse(restored.anyEnabled)
        }
    }

    @Test fun colocatedDirectionsRenderOneMarkerAndKeepEveryViewAvailable() {
        compose.activity.getSharedPreferences("map_layers_preference", Context.MODE_PRIVATE).edit().clear().commit()
        val state = RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), null)
        val first = RoadCamera("se", RoadCameraType.RoadCondition, "Kungsbacka Syd", 57.45, 12.09, 135.0)
        val second = first.copy(id = "nw", direction = 315.0)
        state.cameras = listOf(second, first)
        state.setEnabled(RoadCameraType.Traffic, true)
        assertEquals(listOf(ROAD_CAMERA_MARKER_PREFIX + "se"), state.markers.map { it.id })
        assertEquals(listOf(first, second), state.viewsFor(state.selected(state.markers.single().id)!!))
        state.setEnabled(RoadCameraType.Traffic, false)
        assertTrue(state.markers.isEmpty())
        assertNull(state.selected(ROAD_CAMERA_MARKER_PREFIX + "se"))
    }

    @Test fun speedAndCombinedPhotoLayersPersistAndRenderThroughTheProductionPopup() {
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
        listOf(RoadCameraType.Speed, RoadCameraType.Traffic).forEach { type ->
            compose.onNodeWithTag("map_layers_camera_${type.wire}").performScrollTo().performClick()
            assertTrue(MapLayersPreferenceStore(compose.activity).readRoadCameraLayer(type))
        }
        compose.onNodeWithTag("map_layers_camera_road_condition").assertDoesNotExist()
        assertEquals(3, state.markers.size)
        val restored = RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), null)
        assertTrue(restored.speedEnabled && restored.photoCamerasEnabled)
        compose.onNodeWithTag("map_layers_camera_traffic").performScrollTo().performClick()
        assertEquals(1, state.markers.size)
        assertNull(state.selected(ROAD_CAMERA_MARKER_PREFIX + "traffic"))
        assertNull(state.selected(ROAD_CAMERA_MARKER_PREFIX + "road_condition"))
        assertTrue(state.speedEnabled)
        assertFalse(state.photoCamerasEnabled)
        val disabled = RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), null)
        assertTrue(disabled.speedEnabled)
        assertFalse(disabled.photoCamerasEnabled)
    }
}
