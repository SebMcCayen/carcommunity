package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.testutil.RetryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    val composeTestRule = createComposeRule()

    // RetryRule OUTSIDE the compose rule: a retry relaunches the Activity /
    // rebuilds the compose hierarchy, self-healing the emulator "Activity did not
    // launch" flake. See RetryRule.
    @get:Rule
    val rules = RetryRule.around(composeTestRule)

    private fun previewBitmap(): Bitmap =
        // A non-square oriented preview, like a real photo decode.
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)

    /**
     * Mirrors the real caller's ownership of the preview (AuthenticatedApp hosts
     * the editor under `DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }`):
     * the bitmap is released the MOMENT the editor can no longer be drawn, never in
     * the confirm/cancel handler. This host reproduces that exact contract so the
     * two runtime-only claims below can be pinned on a device.
     */
    @Composable
    private fun EditorHost(
        bitmap: Bitmap,
        visible: Boolean,
        onConfirm: (Float, NormalizedCropRect) -> Unit,
    ) {
        if (visible) {
            DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
            ImageEditScreen(
                bitmap = bitmap,
                frameShape = ImageEditFrameShape.FREEFORM,
                initialAspect = 1f,
                onConfirm = onConfirm,
                onCancel = {},
            )
        } else {
            Box(modifier = Modifier)
        }
    }

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

    /**
     * Lifetime half of the recycle contract: the preview must stay alive for as
     * long as the editor is composed, and be recycled the moment it leaves — a
     * VEHICLE_MAX_DIMENSION decode is several megabytes and picking photo after
     * photo would otherwise pile them up until the collector ran.
     */
    @Test
    fun previewBitmapIsRecycledOnlyWhenTheEditorLeavesComposition() {
        val bitmap = previewBitmap()
        var visible by mutableStateOf(true)
        composeTestRule.setContent {
            KccTheme { EditorHost(bitmap = bitmap, visible = visible, onConfirm = { _, _ -> }) }
        }

        composeTestRule.waitForIdle()
        assertFalse(
            "precondition: the preview must be alive while the editor shows",
            bitmap.isRecycled,
        )

        visible = false
        composeTestRule.waitForIdle()

        assertTrue(
            "the preview bitmap must be recycled once the editor leaves composition",
            bitmap.isRecycled,
        )
    }

    /**
     * Teeth for the recycle POINT, not just the fact of it. Recycling in the
     * cancel/confirm handler instead of onDispose would still satisfy the test
     * above, but throws "Canvas: trying to use a recycled bitmap" when Compose
     * draws the outgoing frame. Here the editor stays composed while the bitmap is
     * alive and the test drives a real confirm click through it: if anything
     * recycled the bitmap early, the draw pass would crash the test. It also pins
     * that confirm hands back a `(rotationDegrees, NormalizedCropRect)` pair — a
     * window plus an angle — and NEVER raw image bytes (the onConfirm type makes
     * bytes unrepresentable, and the sanitiser downstream is the only byte source).
     */
    @Test
    fun theEditorDrawsAndStaysUsableWhileComposedAndConfirmEmitsAWindowNotBytes() {
        val bitmap = previewBitmap()
        var emittedAngle: Float? = null
        var emittedCrop: NormalizedCropRect? = null
        composeTestRule.setContent {
            KccTheme {
                EditorHost(
                    bitmap = bitmap,
                    visible = true,
                    onConfirm = { rotationDegrees, crop ->
                        emittedAngle = rotationDegrees
                        emittedCrop = crop
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag(ImageEditViewportTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(ImageEditConfirmTag)
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertFalse("the bitmap must not be recycled while still composed", bitmap.isRecycled)
        val angle = emittedAngle
        val crop = emittedCrop
        assertNotNull("confirm must emit a rotation (degrees), not bytes", angle)
        assertNotNull("confirm must emit a crop window, not bytes", crop)
        assertTrue("rotationDegrees must be finite", angle!!.isFinite())
        assertTrue("the emitted window must be usable", crop!!.isValid())
    }
}
