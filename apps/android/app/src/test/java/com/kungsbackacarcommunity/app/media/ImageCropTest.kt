package com.kungsbackacarcommunity.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the vehicle-photo crop geometry. This maths decides WHICH
 * PIXELS get uploaded, so it is deliberately Android-free and tested here rather
 * than only through the on-device UI.
 */
class ImageCropTest {

    // A 16:9 crop box, and a 4:3 source photo — the common phone-camera case
    // where the crop genuinely has to discard something.
    private val imageWidth = 1600f
    private val imageHeight = 1200f
    private val boxWidth = 320f
    private val boxHeight = 180f

    private fun rectAt(zoom: Float, offsetX: Float = 0f, offsetY: Float = 0f) =
        ImageCrop.visibleRect(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            boxWidth = boxWidth,
            boxHeight = boxHeight,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
        )

    @Test
    fun `coverScale fills the box on the tighter axis`() {
        // 320/1600 = 0.2 vs 180/1200 = 0.15 — cover takes the larger.
        assertEquals(0.2f, ImageCrop.coverScale(imageWidth, imageHeight, boxWidth, boxHeight), 1e-6f)
    }

    @Test
    fun `coverScale returns zero for an unmeasured box`() {
        assertEquals(0f, ImageCrop.coverScale(imageWidth, imageHeight, 0f, 0f), 0f)
        assertEquals(0f, ImageCrop.coverScale(0f, 0f, boxWidth, boxHeight), 0f)
    }

    @Test
    fun `clampOffset keeps the scaled image covering the box`() {
        // Scaled 640 wide in a 320 box: pan is free within [-320, 0].
        assertEquals(-320f, ImageCrop.clampOffset(640f, 320f, -1000f), 0f)
        assertEquals(0f, ImageCrop.clampOffset(640f, 320f, 100f), 0f)
        assertEquals(-120f, ImageCrop.clampOffset(640f, 320f, -120f), 0f)
    }

    @Test
    fun `clampOffset centres an image smaller than the box`() {
        // No pan can close a gap, so pinning to an edge would only look broken.
        assertEquals(40f, ImageCrop.clampOffset(240f, 320f, -999f), 0f)
    }

    @Test
    fun `clampOffset rejects a non-finite offset`() {
        assertEquals(0f, ImageCrop.clampOffset(640f, 320f, Float.NaN), 0f)
    }

    @Test
    fun `at minimum zoom the crop spans the full width and trims to 16 by 9`() {
        val rect = rectAt(ImageCrop.MIN_ZOOM)
        assertEquals(0f, rect.left, 1e-5f)
        assertEquals(1f, rect.width, 1e-5f)
        // 1600x1200 cropped to 16:9 keeps 900 of 1200 rows.
        assertEquals(0.75f, rect.height, 1e-5f)
        assertTrue(rect.isValid())
    }

    @Test
    fun `the crop window always carries the box aspect ratio`() {
        // The whole point of the fixed box: whatever the zoom, the selected
        // SOURCE region is 16:9, so the cards never letterbox it.
        listOf(1f, 1.5f, 2f, 4f).forEach { zoom ->
            val rect = rectAt(zoom)
            val ratio = (rect.width * imageWidth) / (rect.height * imageHeight)
            assertEquals("zoom=$zoom", ImageCrop.VEHICLE_ASPECT_RATIO, ratio, 1e-3f)
        }
    }

    @Test
    fun `zooming in halves the selected region`() {
        val single = rectAt(1f)
        val double = rectAt(2f)
        assertEquals(single.width / 2f, double.width, 1e-5f)
        assertEquals(single.height / 2f, double.height, 1e-5f)
    }

    @Test
    fun `zoom is clamped to the supported range`() {
        // Beyond MAX_ZOOM the source pixels run out, so the rect must stop
        // shrinking rather than select a sliver.
        assertEquals(rectAt(ImageCrop.MAX_ZOOM).width, rectAt(1000f).width, 1e-6f)
        assertEquals(rectAt(ImageCrop.MIN_ZOOM).width, rectAt(0.01f).width, 1e-6f)
    }

    @Test
    fun `panning moves the window without leaving the source`() {
        val panned = rectAt(zoom = 2f, offsetX = -160f, offsetY = -240f)
        assertTrue("panning right should move the window right", panned.left > 0f)
        assertTrue("panning down should move the window down", panned.top > 0f)
        assertTrue(panned.isValid())
        assertTrue(panned.left + panned.width <= 1f + NormalizedCropRect.EPSILON)
        assertTrue(panned.top + panned.height <= 1f + NormalizedCropRect.EPSILON)
    }

    @Test
    fun `an over-panned offset is clamped to the source edge, never past it`() {
        val overshot = rectAt(zoom = 2f, offsetX = -99999f, offsetY = -99999f)
        assertTrue(overshot.isValid())
        assertEquals(1f, overshot.left + overshot.width, 1e-4f)
        assertEquals(1f, overshot.top + overshot.height, 1e-4f)
    }

    @Test
    fun `a degenerate input falls back to the whole image, never a NaN rect`() {
        // Before layout the box is 0x0. "No measurable crop" must mean "upload
        // the whole image"; a NaN rect would be rejected downstream and the
        // user's photo would silently vanish.
        val unmeasured =
            ImageCrop.visibleRect(imageWidth, imageHeight, 0f, 0f, 1f, 0f, 0f)
        assertEquals(NormalizedCropRect.FULL, unmeasured)
        assertEquals(
            NormalizedCropRect.FULL,
            ImageCrop.visibleRect(0f, 0f, boxWidth, boxHeight, 1f, 0f, 0f),
        )
        assertEquals(
            NormalizedCropRect.FULL,
            ImageCrop.visibleRect(imageWidth, imageHeight, boxWidth, boxHeight, Float.NaN, 0f, 0f),
        )
    }

    @Test
    fun `isValid rejects rects that would crash Bitmap createBitmap`() {
        assertTrue(NormalizedCropRect.FULL.isValid())
        assertFalse("zero width", NormalizedCropRect(0f, 0f, 0f, 1f).isValid())
        assertFalse("negative origin", NormalizedCropRect(-0.1f, 0f, 0.5f, 0.5f).isValid())
        assertFalse("runs past the right edge", NormalizedCropRect(0.8f, 0f, 0.5f, 0.5f).isValid())
        assertFalse("NaN", NormalizedCropRect(Float.NaN, 0f, 0.5f, 0.5f).isValid())
    }

    @Test
    fun `toPixels maps a normalized rect onto the source`() {
        val pixels = NormalizedCropRect(0f, 0.125f, 1f, 0.75f).toPixels(1600, 1200)
        assertNotNull(pixels)
        assertEquals(CropPixels(x = 0, y = 150, width = 1600, height = 900), pixels)
    }

    @Test
    fun `toPixels never runs past the source edge after rounding`() {
        // Teeth: rounding each edge independently can push x+width one pixel
        // past the source, and Bitmap.createBitmap throws on that — a crash on a
        // photo whose only sin was an awkward width.
        val awkward = NormalizedCropRect(0.9995f, 0f, 0.0005f, 1f)
        val pixels = awkward.toPixels(1601, 1200)
        assertNotNull(pixels)
        requireNotNull(pixels)
        assertTrue(pixels.width >= 1)
        assertTrue("x+width must stay inside the source", pixels.x + pixels.width <= 1601)
        assertTrue("y+height must stay inside the source", pixels.y + pixels.height <= 1200)
    }

    @Test
    fun `toPixels rejects an unusable rect or source`() {
        assertNull(NormalizedCropRect(0f, 0f, 0f, 1f).toPixels(100, 100))
        assertNull(NormalizedCropRect.FULL.toPixels(0, 100))
        assertNull(NormalizedCropRect.FULL.toPixels(100, -1))
    }

    @Test
    fun `isFullFrame recognises an uncropped selection`() {
        assertTrue(NormalizedCropRect.FULL.isFullFrame())
        // The minimum-zoom rect on a 4:3 source is NOT full frame — it trims to
        // 16:9, and that trim must not be treated as "no crop" (doing so would
        // let the strip fallback upload the full, untrimmed frame).
        assertFalse(rectAt(ImageCrop.MIN_ZOOM).isFullFrame())
    }

    @Test
    fun `a 16 by 9 source at minimum zoom is a full-frame selection`() {
        val rect =
            ImageCrop.visibleRect(1920f, 1080f, boxWidth, boxHeight, 1f, 0f, 0f)
        assertTrue(rect.isFullFrame())
    }
}
