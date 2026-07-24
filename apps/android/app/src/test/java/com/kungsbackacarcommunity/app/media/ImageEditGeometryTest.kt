package com.kungsbackacarcommunity.app.media

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the gesture editor's geometry ([ImageEditGeometry]). This
 * maths decides which pixels get uploaded AND — with free rotation in play —
 * whether a rotated image ever leaves an empty triangular corner inside the crop
 * frame, so it is tested hard here rather than only through the on-device UI.
 */
class ImageEditGeometryTest {

    private fun radians(deg: Float) = Math.toRadians(deg.toDouble())
    private fun c(deg: Float) = abs(cos(radians(deg))).toFloat()
    private fun s(deg: Float) = abs(sin(radians(deg))).toFloat()

    // ------------------------------------------------------------------
    // minCoverScale — the "no empty corner" floor.
    // ------------------------------------------------------------------

    @Test
    fun `minCoverScale is 1 at zero degrees`() {
        // At no rotation the ordinary cover scale already fills the frame, so the
        // floor is exactly the cover scale (multiple 1.0).
        listOf(1f, 4f / 3f, 16f / 9f, 3f / 4f).forEach { frameAspect ->
            listOf(1f, 4f / 3f, 16f / 9f, 3f / 4f).forEach { imageAspect ->
                assertEquals(
                    "frame=$frameAspect image=$imageAspect",
                    1f,
                    ImageEditGeometry.minCoverScale(0f, frameAspect, imageAspect),
                    1e-4f,
                )
            }
        }
    }

    @Test
    fun `minCoverScale never dips below the no-rotation cover`() {
        // The crux: at every angle the image must be at least as zoomed in as the
        // no-rotation cover, or a rotated image would expose an empty corner.
        listOf(1f, 4f / 3f, 16f / 9f, 3f / 4f, 2f).forEach { frameAspect ->
            listOf(1f, 4f / 3f, 16f / 9f, 3f / 4f, 0.5f).forEach { imageAspect ->
                (0..90 step 5).forEach { deg ->
                    val v = ImageEditGeometry.minCoverScale(deg.toFloat(), frameAspect, imageAspect)
                    assertTrue(
                        "frame=$frameAspect image=$imageAspect deg=$deg gave $v",
                        v >= 1f - 1e-4f,
                    )
                }
            }
        }
    }

    @Test
    fun `minCoverScale grows toward 45 degrees and peaks there for a square`() {
        // A square frame in a square image: the cover multiple is cos+sin, which
        // rises monotonically from 1 at 0 to sqrt(2) at 45 then falls back — the
        // rotated frame's diagonal reach is greatest at 45.
        val at0 = ImageEditGeometry.minCoverScale(0f, 1f, 1f)
        val at15 = ImageEditGeometry.minCoverScale(15f, 1f, 1f)
        val at30 = ImageEditGeometry.minCoverScale(30f, 1f, 1f)
        val at45 = ImageEditGeometry.minCoverScale(45f, 1f, 1f)
        assertTrue(at0 < at15)
        assertTrue(at15 < at30)
        assertTrue(at30 < at45)
        assertEquals("square peaks at sqrt(2) at 45 degrees", 1.41421f, at45, 1e-3f)
    }

    @Test
    fun `minCoverScale is symmetric about zero degrees`() {
        // The cover requirement depends only on |cos| and |sin|, so twisting left
        // or right by the same angle needs the same zoom. (It is NOT 90-degree
        // periodic for a non-square frame/image: a quarter-turn swaps which frame
        // edge binds which image axis.)
        listOf(15f, 30f, 45f, 70f).forEach { deg ->
            val a = ImageEditGeometry.minCoverScale(deg, 4f / 3f, 16f / 9f)
            val b = ImageEditGeometry.minCoverScale(-deg, 4f / 3f, 16f / 9f)
            assertEquals("|angle| symmetry at $deg", a, b, 1e-4f)
        }
    }

    @Test
    fun `minCoverScale is defensive about a degenerate aspect`() {
        assertEquals(1f, ImageEditGeometry.minCoverScale(30f, 0f, 1f), 0f)
        assertEquals(1f, ImageEditGeometry.minCoverScale(30f, 1f, Float.NaN), 0f)
    }

    // ------------------------------------------------------------------
    // coverScaleForFrame — the ACTUAL no-empty-corner proof: at the returned
    // scale the frame is fully inside the rotated image, and just below it is not.
    // ------------------------------------------------------------------

    @Test
    fun `at the cover scale the frame is fully inside the rotated image, with no empty corner`() {
        // A concrete frame and image (screen px on the frame side, preview px on
        // the image side — coverScaleForFrame returns screen-per-preview).
        val fHalfW = 300f
        val fHalfH = 200f
        val iHalfW = 800f // preview half-extents
        val iHalfH = 600f
        (0..90 step 5).forEach { deg ->
            val angle = deg.toFloat()
            val cover =
                ImageEditGeometry.coverScaleForFrame(angle, fHalfW, fHalfH, iHalfW, iHalfH)
            val scaledHalfW = cover * iHalfW
            val scaledHalfH = cover * iHalfH
            // Frame's half-extent along each IMAGE axis once rotated by -angle.
            val frameExtentX = c(angle) * fHalfW + s(angle) * fHalfH
            val frameExtentY = s(angle) * fHalfW + c(angle) * fHalfH
            // Covered iff the scaled image reaches those extents (no empty corner).
            assertTrue(
                "deg=$deg: image must cover the frame on X (got $scaledHalfW need $frameExtentX)",
                scaledHalfW >= frameExtentX - 1e-2f,
            )
            assertTrue(
                "deg=$deg: image must cover the frame on Y (got $scaledHalfH need $frameExtentY)",
                scaledHalfH >= frameExtentY - 1e-2f,
            )
            // ...and the cover scale is TIGHT — 1% less leaves a gap on some axis.
            val tooSmall = cover * 0.99f
            val gap =
                (tooSmall * iHalfW < frameExtentX - 1e-2f) ||
                    (tooSmall * iHalfH < frameExtentY - 1e-2f)
            assertTrue("deg=$deg: cover scale must be the tight minimum", gap)
        }
    }

    // ------------------------------------------------------------------
    // resolveCrop — transform -> NormalizedCropRect.
    // ------------------------------------------------------------------

    @Test
    fun `resolveCrop with a centred cover frame selects the whole rotated frame`() {
        // Frame == image (cover, no zoom), centred, no rotation -> the full image.
        val rect =
            ImageEditGeometry.resolveCrop(
                angleDeg = 0f,
                dX = 0f,
                dY = 0f,
                frameHalfW = 100f,
                frameHalfH = 100f,
                imageScaledHalfW = 100f,
                imageScaledHalfH = 100f,
            )
        assertEquals(0f, rect.left, 1e-5f)
        assertEquals(0f, rect.top, 1e-5f)
        assertEquals(1f, rect.width, 1e-5f)
        assertEquals(1f, rect.height, 1e-5f)
        assertTrue(rect.isValid())
    }

    @Test
    fun `resolveCrop with a centred half frame selects the centre quarter`() {
        val rect =
            ImageEditGeometry.resolveCrop(
                angleDeg = 0f,
                dX = 0f,
                dY = 0f,
                frameHalfW = 50f,
                frameHalfH = 50f,
                imageScaledHalfW = 100f,
                imageScaledHalfH = 100f,
            )
        assertEquals(0.25f, rect.left, 1e-5f)
        assertEquals(0.25f, rect.top, 1e-5f)
        assertEquals(0.5f, rect.width, 1e-5f)
        assertEquals(0.5f, rect.height, 1e-5f)
        assertTrue(rect.isValid())
    }

    @Test
    fun `resolveCrop is always valid and within the unit square across a gesture sweep`() {
        listOf(0f, 12f, 33f, 45f, 90f, 187f, -40f).forEach { angle ->
            listOf(-120f, 0f, 90f).forEach { dx ->
                listOf(-60f, 0f, 75f).forEach { dy ->
                    val rect =
                        ImageEditGeometry.resolveCrop(
                            angleDeg = angle,
                            dX = dx,
                            dY = dy,
                            frameHalfW = 160f,
                            frameHalfH = 90f,
                            imageScaledHalfW = 400f,
                            imageScaledHalfH = 300f,
                        )
                    assertTrue("angle=$angle dx=$dx dy=$dy invalid", rect.isValid())
                    assertTrue(rect.left >= 0f && rect.top >= 0f)
                    assertTrue(rect.left + rect.width <= 1f + NormalizedCropRect.EPSILON)
                    assertTrue(rect.top + rect.height <= 1f + NormalizedCropRect.EPSILON)
                }
            }
        }
    }

    @Test
    fun `resolveCrop falls back to full frame on degenerate input, never a NaN rect`() {
        assertEquals(
            NormalizedCropRect.FULL,
            ImageEditGeometry.resolveCrop(0f, 0f, 0f, 100f, 100f, 0f, 0f),
        )
        assertEquals(
            NormalizedCropRect.FULL,
            ImageEditGeometry.resolveCrop(Float.NaN, 0f, 0f, 100f, 100f, 100f, 100f),
        )
        assertEquals(
            NormalizedCropRect.FULL,
            ImageEditGeometry.resolveCrop(0f, Float.NaN, 0f, 100f, 100f, 100f, 100f),
        )
    }

    // ------------------------------------------------------------------
    // The anti-stretch rule: the SELECTED SOURCE REGION always carries the crop
    // FRAME's aspect — never both dimensions forced. This is the test that would
    // have caught the previous "weird sizing" bug.
    // ------------------------------------------------------------------

    @Test
    fun `the selected region always carries the frame aspect, at any rotation`() {
        val iHalfW = 400f
        val iHalfH = 300f
        // (frameHalfW, frameHalfH) covering a spread of aspects.
        val frames = listOf(160f to 90f, 100f to 100f, 90f to 160f, 200f to 150f)
        frames.forEach { (fw, fh) ->
            listOf(0f, 17f, 45f, 90f, 130f).forEach { angle ->
                val rect =
                    ImageEditGeometry.resolveCrop(
                        angleDeg = angle,
                        dX = 0f,
                        dY = 0f,
                        frameHalfW = fw,
                        frameHalfH = fh,
                        imageScaledHalfW = iHalfW,
                        imageScaledHalfH = iHalfH,
                    )
                assertTrue(rect.isValid())
                // The rotated bounding box the rect is normalized against.
                val rhx = c(angle) * iHalfW + s(angle) * iHalfH
                val rhy = s(angle) * iHalfW + c(angle) * iHalfH
                // Region pixel size = fraction * full bbox extent.
                val regionW = rect.width * (2f * rhx)
                val regionH = rect.height * (2f * rhy)
                assertEquals(
                    "frame=$fw x $fh angle=$angle: region must carry the frame aspect",
                    fw / fh,
                    regionW / regionH,
                    1e-3f,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // outputDimensions — longest side capped, aspect derived (never both forced).
    // ------------------------------------------------------------------

    @Test
    fun `outputDimensions caps the longest side and preserves aspect`() {
        // 2000x1000 capped at 1600 -> 1600x800: longest exactly the cap, aspect 2.
        val (w, h) = ImageEditGeometry.outputDimensions(2000, 1000, 1600)
        assertEquals(1600, w)
        assertEquals(800, h)
        assertEquals(2000f / 1000f, w.toFloat() / h.toFloat(), 1e-3f)
    }

    @Test
    fun `outputDimensions never upscales a region already within the cap`() {
        val (w, h) = ImageEditGeometry.outputDimensions(800, 600, 1600)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun `outputDimensions output aspect equals the crop aspect across a sweep`() {
        // The contract that pins the anti-stretch rule: whatever the crop region,
        // the output aspect equals the crop aspect and the longest side never
        // exceeds the cap. Forcing BOTH dimensions (the old bug) would fail this.
        val maxDim = 1024
        listOf(
            2400 to 1350, // 16:9
            1800 to 1800, // square
            900 to 1600, // portrait
            3000 to 400, // panorama
        ).forEach { (cw, ch) ->
            val (w, h) = ImageEditGeometry.outputDimensions(cw, ch, maxDim)
            assertTrue("longest <= cap for $cw x $ch (got $w x $h)", maxOf(w, h) <= maxDim)
            val expected = cw.toFloat() / ch.toFloat()
            assertEquals(
                "output aspect must equal the crop aspect for $cw x $ch (got $w x $h)",
                expected,
                w.toFloat() / h.toFloat(),
                // Relative tolerance: capping to integer pixels rounds the derived
                // side, which is coarser for extreme aspects (a 1px error on a
                // 137px side of a 7.5:1 panorama).
                expected * 0.03f,
            )
        }
    }

    @Test
    fun `outputDimensions is defensive about a degenerate crop`() {
        assertEquals(0 to 0, ImageEditGeometry.outputDimensions(0, 100, 1024))
        assertEquals(0 to 0, ImageEditGeometry.outputDimensions(100, 100, 0))
    }

    // ------------------------------------------------------------------
    // clampImageOffset — the pan never lets a gap into the frame.
    // ------------------------------------------------------------------

    @Test
    fun `clampImageOffset keeps an over-pan inside the covering image`() {
        // Image scaled half 200 vs frame half 100, no rotation: the frame centre
        // may sit at most 100px from the image centre before a gap appears.
        val (x, y) =
            ImageEditGeometry.clampImageOffset(
                angleDeg = 0f,
                dX = 9999f,
                dY = -9999f,
                frameHalfW = 100f,
                frameHalfH = 100f,
                imageScaledHalfW = 200f,
                imageScaledHalfH = 200f,
            )
        assertEquals(100f, x, 1e-3f)
        assertEquals(-100f, y, 1e-3f)
    }

    @Test
    fun `clampImageOffset centres the frame when the image cannot cover it`() {
        // Image smaller than the frame on both axes: no pan can close the gap, so
        // the offset is centred (0) rather than pinned to an edge.
        val (x, y) =
            ImageEditGeometry.clampImageOffset(
                angleDeg = 0f,
                dX = 50f,
                dY = 50f,
                frameHalfW = 100f,
                frameHalfH = 100f,
                imageScaledHalfW = 80f,
                imageScaledHalfH = 80f,
            )
        assertEquals(0f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    @Test
    fun `clampImageOffset leaves an in-range offset untouched`() {
        val (x, y) =
            ImageEditGeometry.clampImageOffset(
                angleDeg = 0f,
                dX = 30f,
                dY = -20f,
                frameHalfW = 100f,
                frameHalfH = 100f,
                imageScaledHalfW = 200f,
                imageScaledHalfH = 200f,
            )
        assertEquals(30f, x, 1e-3f)
        assertEquals(-20f, y, 1e-3f)
    }

    @Test
    fun `clampImageOffset keeps the frame covered even under rotation`() {
        // After clamping, the frame's rotated extent must fit inside the image on
        // both axes — the pan cannot pull an empty corner into the frame.
        val fHalfW = 120f
        val fHalfH = 80f
        val iHalfW = 500f
        val iHalfH = 380f
        listOf(15f, 30f, 45f, 60f).forEach { angle ->
            val (x, y) =
                ImageEditGeometry.clampImageOffset(
                    angleDeg = angle,
                    dX = 4000f,
                    dY = 2500f,
                    frameHalfW = fHalfW,
                    frameHalfH = fHalfH,
                    imageScaledHalfW = iHalfW,
                    imageScaledHalfH = iHalfH,
                )
            // Frame centre back in image-local axes.
            val rad = radians(angle)
            val localX = (cos(rad) * x + sin(rad) * y).toFloat()
            val localY = (-sin(rad) * x + cos(rad) * y).toFloat()
            val frameExtentX = c(angle) * fHalfW + s(angle) * fHalfH
            val frameExtentY = s(angle) * fHalfW + c(angle) * fHalfH
            assertTrue(
                "angle=$angle: frame must stay inside image on X",
                abs(localX) + frameExtentX <= iHalfW + 1e-2f,
            )
            assertTrue(
                "angle=$angle: frame must stay inside image on Y",
                abs(localY) + frameExtentY <= iHalfH + 1e-2f,
            )
        }
    }
}
