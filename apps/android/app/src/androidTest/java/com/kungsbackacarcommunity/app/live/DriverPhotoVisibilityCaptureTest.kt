package com.kungsbackacarcommunity.app.live

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.convoy.CONVOY_EDGE_ARROW_TAG
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.map.ConvoyMemberPosition
import com.kungsbackacarcommunity.app.map.DriverPhotoRect
import com.kungsbackacarcommunity.app.shell.MapCameraSnapshot
import com.kungsbackacarcommunity.app.shell.MapPoint
import com.kungsbackacarcommunity.app.shell.MapProjection
import com.kungsbackacarcommunity.app.shell.MapScreenPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Component QA only: synthetic geometry, production photo fallback, no auth/cloud or Mapbox. */
@RunWith(AndroidJUnit4::class)
class DriverPhotoVisibilityCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureSelectedLeaderDesignAndCheckUidBinding() {
        val scenario = InstrumentationRegistry.getArguments().getString("driverPhotoScenario") ?: "normal"
        require(scenario in setOf("normal", "dense", "edge"))
        val count = if (scenario == "dense") 12 else 3
        val calls = mutableListOf<String>()
        val live = mutableStateOf(true)
        compose.setContent { KccTheme(darkTheme = true) {
            val density = LocalDensity.current.density
            val ownX = if (scenario == "edge") 22.0 else 195.0
            val projection = remember { FixtureProjection(density, ownX) }
            val now = remember { System.currentTimeMillis() }
            val markers = remember {
                (0 until count).map { index ->
                    val x = if (scenario == "dense" || scenario == "edge") ownX else listOf(185.0, 205.0, 175.0)[index]
                    val y = if (scenario == "dense" || scenario == "edge") 400.0 else listOf(280.0, 430.0, 590.0)[index]
                    ConvoyMemberPosition("driver-$index", 57.0 - (y - 400) / 100_000, 12.0 + (x - 195) / 100_000,
                        displayName = "Driver $index", updatedAtMillis = now)
                }
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CompositionLocalProvider(LocalDriverPhotoExclusions provides listOf(
                    DriverPhotoRect(320.0 * density, 80.0 * density, 380.0 * density, 260.0 * density))) {
                    LiveDriverMapOverlay(projection,
                        convoy = if (live.value) markers.filterIndexed { i, _ -> i % 2 == 0 } +
                            ConvoyMemberPosition("offscreen", 57.01, 12.0, updatedAtMillis = now) else emptyList(),
                        nearby = if (live.value) markers.filterIndexed { i, _ -> i % 2 == 1 }.map {
                            LiveMarker(it.uid, it.latitude, it.longitude, it.displayName, recordedAtIso = java.time.Instant.ofEpochMilli(now).toString())
                        } else emptyList(), onDriverTap = { calls += it })
                }
                // A source-position locator for this isolated component capture.
                // The running app uses its native Mapbox/SDK-asset puck.
                Box(Modifier.offset(x = (ownX - 13).dp, y = 387.dp).size(26.dp)
                    .background(Color(0xff2b7cff), CircleShape).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Navigation, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        } }
        compose.waitForIdle()
        for (index in 0 until count) compose.onNodeWithTag(NEARBY_LIVE_CHIP_TAG + "driver-$index").assertHasClickAction()
        compose.onNodeWithTag(CONVOY_EDGE_ARROW_TAG + "offscreen").assertIsDisplayed()
        compose.onNodeWithTag(NEARBY_LIVE_CHIP_TAG + "driver-1").performClick()
        compose.runOnIdle { assertEquals(listOf("driver-1"), calls) }
        val file = File(compose.activity.getExternalFilesDir(null), "driver-photos-$scenario.png")
        file.outputStream().use { check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
        compose.runOnIdle { live.value = false }
        compose.onNodeWithTag(NEARBY_LIVE_CHIP_TAG + "driver-1").assertDoesNotExist()
    }

    private class FixtureProjection(private val density: Float, ownX: Double) : MapProjection {
        override val cameraSnapshot = MutableStateFlow<MapCameraSnapshot?>(MapCameraSnapshot(57.0, 12.0, 16.0, 0.0, 0.0))
        override val renderedOwnPoint = MutableStateFlow<MapPoint?>(MapPoint(12.0 + (ownX - 195) / 100_000, 57.0))
        override fun screenPositionFor(latitude: Double, longitude: Double): MapScreenPoint = MapScreenPoint(
            ((longitude - 12.0) * 100_000 + 195).toFloat() * density,
            ((57.0 - latitude) * 100_000 + 400).toFloat() * density)
    }
}
