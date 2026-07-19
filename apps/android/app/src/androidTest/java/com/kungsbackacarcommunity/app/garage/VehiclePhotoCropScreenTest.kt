package com.kungsbackacarcommunity.app.garage

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.media.NormalizedCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device coverage for the vehicle-photo crop step.
 *
 * The gesture FEEL (pinch/drag) is not covered here — only its result is
 * observable, and the maths behind it lives in `ImageCropTest`. What is covered
 * is the part that is only true at runtime: the preview bitmap's lifetime, and
 * that confirming emits a window rather than an image.
 */
@RunWith(AndroidJUnit4::class)
class VehiclePhotoCropScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun previewBitmap(): Bitmap =
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)

    /**
     * Mirrors GarageRoute's ownership of the preview: the bitmap is released by
     * a `DisposableEffect` sitting alongside the screen, so it is freed the
     * moment the screen can no longer be drawn.
     */
    @Composable
    private fun CropHost(bitmap: Bitmap, visible: Boolean, onConfirm: (NormalizedCropRect) -> Unit) {
        if (visible) {
            DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
            VehiclePhotoCropScreen(bitmap = bitmap, onConfirm = onConfirm, onCancel = {})
        } else {
            Box(modifier = Modifier)
        }
    }

    @Test
    fun previewBitmapIsRecycledWhenTheCropScreenLeaves() {
        val bitmap = previewBitmap()
        var visible by mutableStateOf(true)
        composeTestRule.setContent {
            KccTheme { CropHost(bitmap = bitmap, visible = visible, onConfirm = {}) }
        }

        composeTestRule.waitForIdle()
        assertFalse(
            "precondition: the preview must be alive while the crop screen shows",
            bitmap.isRecycled,
        )

        visible = false
        composeTestRule.waitForIdle()

        assertTrue(
            "the preview bitmap must be recycled once the crop screen is gone — a " +
                "VEHICLE_MAX_DIMENSION decode is several megabytes and picking photo " +
                "after photo would otherwise pile them up until the collector ran",
            bitmap.isRecycled,
        )
    }

    /**
     * Teeth for the recycle POINT, not just the fact of it. Recycling in the
     * cancel/confirm handler instead of onDispose would still satisfy the test
     * above, but throws "Canvas: trying to use a recycled bitmap" when Compose
     * draws the outgoing frame. Here the screen stays composed while the bitmap
     * is alive, and the test drives a real click through it: if anything
     * recycled the bitmap early, the draw pass would crash the test.
     */
    @Test
    fun theCropScreenDrawsAndStaysUsableWhileComposed() {
        val bitmap = previewBitmap()
        var confirmed: NormalizedCropRect? = null
        composeTestRule.setContent {
            KccTheme {
                CropHost(bitmap = bitmap, visible = true, onConfirm = { confirmed = it })
            }
        }

        composeTestRule.onNodeWithText(str(R.string.garage_photoCropConfirm)).performClick()
        composeTestRule.waitForIdle()

        assertFalse("the bitmap must not be recycled while still composed", bitmap.isRecycled)
        assertNotNull("confirming must emit a crop window", confirmed)
        val rect = requireNotNull(confirmed)
        assertTrue("the emitted window must be usable", rect.isValid())
        // Untouched gestures = the fully zoomed-out 16:9 window. A 400x300 (4:3)
        // preview keeps its full width and 3/4 of its height.
        assertEquals(0f, rect.left, 1e-3f)
        assertEquals(1f, rect.width, 1e-3f)
        assertEquals(0.75f, rect.height, 1e-2f)
    }
}
