package com.kungsbackacarcommunity.app.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.generated.LocationComponentSettings
import com.mapbox.maps.plugin.locationcomponent.generated.LocationComponentSettingsBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the pinned SDK's real setters; only the native renderer boundary is counted. */
@RunWith(AndroidJUnit4::class)
class PuckSettingsUpdateTest {
    private val blue = 0xFF4A90E2.toInt()
    private val green = 0xFF4CAF50.toInt()

    @Test
    fun repeatedLiveFramesDoNotReapplySettingsOrReplaceThePuck() {
        val settings = RendererSettings(green)
        val puck = settings.locationPuck
        repeat(1_200) { applyPuckPulseColor(settings, green) }
        assertEquals(0, settings.applies)
        assertSame(puck, settings.locationPuck)
        assertTrue(settings.enabled)
        assertTrue(settings.pulsingEnabled)
        assertTrue(settings.puckBearingEnabled)
        assertEquals(PuckBearing.COURSE, settings.puckBearing)

        // This is the old render path, using the same SDK: every no-op bulk
        // update crosses the boundary that recreates the native puck layer.
        repeat(3) { settings.updateSettings { pulsingColor = green } }
        assertEquals(3, settings.applies)
    }

    @Test
    fun sharingTransitionsApplyOnceAndRepeatedFramesStayQuiet() {
        val settings = RendererSettings(blue)
        applyPuckPulseColor(settings, green)
        assertEquals(green, settings.pulsingColor)
        assertEquals(1, settings.applies)
        repeat(120) { applyPuckPulseColor(settings, green) }
        assertEquals(1, settings.applies)
        applyPuckPulseColor(settings, blue)
        assertEquals(blue, settings.pulsingColor)
        assertEquals(2, settings.applies)
        repeat(120) { applyPuckPulseColor(settings, blue) }
        assertEquals(2, settings.applies)
    }

    @Test
    fun nativeReentryDoesNotReapplyTheSameColor() {
        val settings = RendererSettings(blue)
        settings.onApply = {
            if (settings.applies < 10) applyPuckPulseColor(settings, green)
        }
        applyPuckPulseColor(settings, green)
        assertEquals(1, settings.applies)
    }

    @Test
    fun aNewMapAndSettingsChangedByLifecycleAreReadFromTheSdk() {
        val first = RendererSettings(blue)
        applyPuckPulseColor(first, green)
        val recreated = RendererSettings(blue)
        applyPuckPulseColor(recreated, green)
        assertEquals(1, recreated.applies)
        // A cached "last applied color" in the app would miss this SDK change.
        recreated.updateSettings { pulsingColor = blue }
        applyPuckPulseColor(recreated, green)
        assertEquals(green, recreated.pulsingColor)
        assertEquals(3, recreated.applies)
    }

    @Test
    fun visibilityChangesApplyOnceWithoutTheBulkUpdatesSecondApplication() {
        val settings = RendererSettings(green)
        settings.enabled = false
        assertEquals(1, settings.applies)
        repeat(120) { settings.enabled = false }
        assertEquals(1, settings.applies)
        settings.enabled = true
        assertEquals(2, settings.applies)
    }

    private class RendererSettings(color: Int) : LocationComponentSettingsBase() {
        override var internalSettings: LocationComponentSettings =
            LocationComponentSettings.Builder(LocationPuck2D())
                .setEnabled(true)
                .setPulsingEnabled(true)
                .setPulsingColor(color)
                .setPuckBearingEnabled(true)
                .setPuckBearing(PuckBearing.COURSE)
                .build()
        var applies = 0
        var onApply: (() -> Unit)? = null

        override fun applySettings() {
            applies++
            onApply?.invoke()
        }
    }
}
