package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h900dp-mdpi")
class MapDensityOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun groupChooserPagesEveryMemberAndOpensTheExactOriginalDetail() {
        val surface = StubMapSurface(autoLoad = false)
        surface.setCameraSnapshotForTest(MapCameraSnapshot.of(57.0, 12.0, 15.0, 0.0, 0.0))
        surface.setProjectionForTest { _, _ -> MapScreenPoint(190f, 220f) }
        val source = (0..24).map { index ->
            MapIncidentMarker("i" + index.toString().padStart(2, '0'), 12.0, 57.0,
                0xFFD32F2F.toInt(), R.drawable.ic_incident_accident, 0xFFFFFFFF.toInt())
        }
        compose.setContent { KccTheme { Box(Modifier.requiredSize(390.dp, 850.dp)) {
            MapDensityOverlay(surface, source)
        } } }
        compose.onNodeWithTag("map_density_group_incident:i00").performClick()
        assertNull(surface.incidentTap.value)
        compose.onNodeWithTag("map_density_item_incident:i00").assertExists()
        compose.onNodeWithTag("map_density_item_incident:i20").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.mapDensity_next)).performClick()
        compose.onNodeWithTag("map_density_item_incident:i20").performClick()
        compose.runOnIdle {
            assertEquals("i20", surface.incidentTap.value)
            assertEquals(source, surface.incidentMarkers.value)
            assertNull(surface.crownTap.value)
            assertNull(surface.placeRequest.value)
        }
    }
}
