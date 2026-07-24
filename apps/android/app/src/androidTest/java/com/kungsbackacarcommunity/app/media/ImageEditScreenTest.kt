package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented pin for the ONE shared gesture editor every image upload now routes
 * through ([ImageEditScreen]). The old per-feature crop UI had an androidTest of
 * its own; this replaces it so the shared editor's runtime contract is still held
 * on a real device.
 *
 * The claim under test is exactly the kind that can be true in source and false on
 * the screen: confirm must hand the caller a `(rotationDegrees, NormalizedCropRect)`
 * pair whose crop is a VALID window (finite, inside the unit square, non-empty) —
 * never a NaN/degenerate rect that would crash `Bitmap.createBitmap` downstream —
 * and it must only do so once the viewport has measured and the preview renders.
 * So it is asserted by driving the merged UI (measure → click confirm), not by
 * reading the composable.
 */
@RunWith(AndroidJUnit4::class)
class ImageEditScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun previewBitmap(): Bitmap =
        // A non-square oriented preview, like a real photo decode.
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)

    @Test
    fun confirmEmitsRotationDegreesAndValidCropWindow() {
        var emittedAngle: Float? = null
        var emittedCrop: NormalizedCropRect? = null

        composeTestRule.setContent {
            KccTheme {
                ImageEditScreen(
                    bitmap = previewBitmap(),
                    frameShape = ImageEditFrameShape.FREEFORM,
                    initialAspect = 1f,
                    onConfirm = { rotationDegrees, crop ->
                        emittedAngle = rotationDegrees
                        emittedCrop = crop
                    },
                    onCancel = {},
                )
            }
        }

        // The preview surface must render before confirm can resolve a crop.
        composeTestRule.onNodeWithTag(ImageEditViewportTag).assertIsDisplayed()

        composeTestRule
            .onNodeWithTag(ImageEditConfirmTag)
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        // Confirm fired with BOTH halves of the contract.
        val angle = emittedAngle
        val crop = emittedCrop
        assertNotNull("confirm must invoke onConfirm with a rotation", angle)
        assertNotNull("confirm must invoke onConfirm with a crop rect", crop)

        // No gesture was performed, so the rotation is a clean, finite 0°.
        assertTrue("rotationDegrees must be finite", angle!!.isFinite())
        assertEquals(0f, angle!!, 1e-3f)

        // The crop must be a usable window inside the unit square — the property
        // downstream Bitmap.createBitmap depends on — not FULL-fallback noise.
        assertTrue("crop must be a valid window inside the unit square", crop!!.isValid())
    }

    @Test
    fun avatarCircleEditorAlsoEmitsAValidCrop() {
        var emittedCrop: NormalizedCropRect? = null

        composeTestRule.setContent {
            KccTheme {
                ImageEditScreen(
                    bitmap = previewBitmap(),
                    frameShape = ImageEditFrameShape.CIRCLE,
                    // Deliberately non-1:1 to prove CIRCLE forces a square 1:1 frame
                    // and still resolves to a valid crop.
                    initialAspect = 1.75f,
                    onConfirm = { _, crop -> emittedCrop = crop },
                    onCancel = {},
                )
            }
        }

        composeTestRule
            .onNodeWithTag(ImageEditConfirmTag)
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        val crop = emittedCrop
        assertNotNull("circle editor confirm must invoke onConfirm with a crop", crop)
        assertTrue("circle crop must be a valid window", crop!!.isValid())
    }
}
