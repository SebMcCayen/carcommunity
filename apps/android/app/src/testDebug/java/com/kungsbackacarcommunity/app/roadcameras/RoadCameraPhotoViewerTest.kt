package com.kungsbackacarcommunity.app.roadcameras

import android.graphics.Bitmap
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.DockedMapSheetHost
import com.kungsbackacarcommunity.app.shell.MapLayersPreferenceStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoadCameraPhotoViewerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val camera = RoadCamera("traffic-test", RoadCameraType.Traffic, "Test camera", 57.0, 12.0)
    private val photoCalls = AtomicInteger()
    private val repository = object : RoadCameraRepository {
        override suspend fun catalog() = error("The photo viewer must not fetch a catalog")
        override suspend fun photo(cameraId: String): RoadCameraPhoto {
            photoCalls.incrementAndGet()
            val bitmap = Bitmap.createBitmap(385, 290, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            bitmap.recycle()
            return RoadCameraPhoto(bytes, "image/jpeg", null)
        }
    }

    @Test fun zoomingAnOpenGroupKeepsTheChosenCameraUntilItIsRemovedFromTheCatalog() {
        compose.activity.getSharedPreferences("map_layers_preference", Context.MODE_PRIVATE).edit().clear().commit()
        val first = camera.copy(id = "a", name = "First site", direction = 135.0)
        val second = camera.copy(id = "b", name = "Second site", latitude = first.latitude + 0.0008, direction = 315.0)
        val state = RoadCameraLayerState(MapLayersPreferenceStore(compose.activity), repository)
        state.cameras = listOf(first, second)
        state.setEnabled(RoadCameraType.Traffic, true)
        val zoom = mutableStateOf(13.0)
        compose.setContent {
            state.markersNear(first.latitude, first.longitude, zoom.value)
            KccTheme { DockedMapSheetHost {
                RoadCameraDetailsSheet(first, repository, views = rememberRoadCameraSelectionViews(state, first)) {}
            } }
        }
        awaitPhoto()
        compose.onNodeWithTag("road_camera_direction_b").performScrollTo().performClick()
        compose.waitUntil(5_000) { photoCalls.get() == 2 }
        awaitPhoto()
        compose.runOnIdle { zoom.value = 17.0 }
        compose.onNodeWithTag("road_camera_direction_b").assertIsSelected()
        assertEquals(2, photoCalls.get())
        compose.runOnIdle { state.cameras = listOf(first) }
        compose.onNodeWithTag("road_camera_direction_b").assertDoesNotExist()
        compose.waitUntil(5_000) { photoCalls.get() == 3 }
    }

    @Test fun openingAndClosingLargerPhotoKeepsTheSheetAndReusesTheLoadedImage() {
        var sheetDismisses = 0
        compose.setContent {
            KccTheme { DockedMapSheetHost { RoadCameraDetailsSheet(camera, repository) { sheetDismisses++ } } }
        }
        awaitPhoto()
        val previewBounds = compose.onNodeWithTag("road_camera_photo").getUnclippedBoundsInRoot()
        repeat(2) {
            compose.onNodeWithTag("road_camera_photo").performScrollTo().performClick()
            compose.onNodeWithTag("road_camera_photo_viewer").assertIsDisplayed()
            val expandedBounds = compose.onNodeWithTag("road_camera_photo_expanded").getUnclippedBoundsInRoot()
            assertTrue(expandedBounds.right - expandedBounds.left > previewBounds.right - previewBounds.left)
            assertTrue(expandedBounds.bottom - expandedBounds.top > previewBounds.bottom - previewBounds.top)
            compose.onNodeWithTag("road_camera_photo_close").assertIsDisplayed().performClick()
            compose.onNodeWithTag("road_camera_photo_viewer").assertDoesNotExist()
            compose.onNodeWithTag("road_camera_details").assertIsDisplayed()
        }
        assertEquals(1, photoCalls.get())
        assertEquals(0, sheetDismisses)
    }

    @Test fun directionButtonsOnlyAppearForMultipleViewsAndLoadTheChosenCamera() {
        val first = camera.copy(id = "se", direction = 135.0)
        val second = camera.copy(id = "nw", direction = 315.0)
        val requested = mutableListOf<String>()
        val source = object : RoadCameraRepository {
            override suspend fun catalog() = error("No catalog fetch from direction buttons")
            override suspend fun photo(cameraId: String): RoadCameraPhoto {
                requested.add(cameraId)
                return repository.photo(cameraId)
            }
        }
        val views = mutableStateOf(listOf(first, second))
        compose.setContent {
            KccTheme { DockedMapSheetHost {
                RoadCameraDetailsSheet(first, source, views = views.value) {}
            } }
        }
        awaitPhoto()
        compose.onNodeWithTag("road_camera_direction_se").assertIsSelected()
        compose.onNodeWithTag("road_camera_direction_nw").performScrollTo().performClick()
        compose.waitUntil(5_000) { requested.size == 2 }
        awaitPhoto()
        compose.onNodeWithTag("road_camera_direction_nw").assertIsSelected().performClick()
        compose.waitForIdle()
        assertEquals(listOf("se", "nw"), requested)
        compose.runOnIdle { views.value = listOf(first) }
        compose.onNodeWithTag("road_camera_direction_se").assertDoesNotExist()
        compose.onNodeWithTag("road_camera_direction_nw").assertDoesNotExist()
    }

    @Test fun switchingCameraClosesThePreviousExpandedPhoto() {
        val selected = mutableStateOf(camera)
        compose.setContent {
            KccTheme { DockedMapSheetHost { RoadCameraDetailsSheet(selected.value, repository) {} } }
        }
        awaitPhoto()
        compose.onNodeWithTag("road_camera_photo").performScrollTo().performClick()
        compose.onNodeWithTag("road_camera_photo_viewer").assertIsDisplayed()
        compose.runOnIdle { selected.value = camera.copy(id = "other-camera", name = "Other camera") }
        awaitPhoto()
        compose.onNodeWithTag("road_camera_photo_viewer").assertDoesNotExist()
        compose.onNodeWithTag("road_camera_details").assertIsDisplayed()
        assertEquals(2, photoCalls.get())
    }

    private fun awaitPhoto() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("road_camera_photo").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
