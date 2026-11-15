package com.kungsbackacarcommunity.app.roadcameras

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.IncidentMarkerBitmaps
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoadCameraGroupBitmapTest {
    @Test fun countsAndOpposingArrowsHaveSeparateCacheIdentitiesAndRoomAtEveryDensity() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = R.drawable.ic_camera_speed
        val color = cameraColor(RoadCameraType.Speed)
        val ink = cameraGlyphColor(RoadCameraType.Speed)
        val solo = IncidentMarkerBitmaps.imageId(icon, color, ink)
        val pair = IncidentMarkerBitmaps.imageId(icon, color, ink, groupCount = 2, opposingDirections = true)
        val cluster = IncidentMarkerBitmaps.imageId(icon, color, ink, groupCount = 2)
        assertEquals(3, setOf(solo, pair, cluster).size)
        assertNotEquals(cluster, IncidentMarkerBitmaps.imageId(icon, color, ink, groupCount = 3))

        for (dpi in listOf(160, 213, 320, 480)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { densityDpi = dpi })
            val small = requireNotNull(IncidentMarkerBitmaps.create(context, icon, color, ink, groupCount = 2))
            val opposing = requireNotNull(IncidentMarkerBitmaps.create(context, icon, color, ink,
                groupCount = 2, opposingDirections = true))
            val large = requireNotNull(IncidentMarkerBitmaps.create(context, icon, color, ink, groupCount = 10_000))
            try {
                assertTrue(opposing.width > small.width)
                assertTrue(large.width > small.width)
                assertEquals(small.height, opposing.height)
                assertEquals(small.height, large.height)
                for (bitmap in listOf(small, opposing, large)) {
                    assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                    assertEquals(255, Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
                }
                if (dpi == 320) System.getenv("ROAD_CAMERA_BITMAP_OUTPUT")?.let { destination ->
                    File(destination).mkdirs()
                    for ((name, bitmap) in listOf("group" to small, "pair" to opposing, "large-count" to large)) {
                        File(destination, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            } finally {
                small.recycle(); opposing.recycle(); large.recycle()
            }
        }
    }
}
