package com.kungsbackacarcommunity.app.clubs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.kungsbackacarcommunity.app.media.NormalizedCropRect
import com.kungsbackacarcommunity.app.media.PickedImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual sanitizer used by the confirmed club-logo crop; no remote upload. */
class ClubLogoPreparationTest {
    private fun source(): PickedImage {
        val image = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(1600 * 800) { if (it % 1600 < 800) Color.RED else Color.BLUE }
            image.setPixels(pixels, 0, 1600, 0, 0, 1600, 800)
            return PickedImage(ByteArrayOutputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
            }, "image/png")
        } finally { image.recycle() }
    }

    @Test fun selectedSquareIsSavedInsteadOfWholeSource() = runBlocking {
        val output = prepareClubLogo(source(), NormalizedCropRect(0.5f, 0f, 0.5f, 1f), 0f)
        assertEquals("image/jpeg", output.contentType)
        assertTrue(output.sizeBytes in 1..CLUB_LOGO_UPLOAD_BYTES.toLong())
        val decoded = BitmapFactory.decodeByteArray(output.bytes, 0, output.bytes.size)
        try {
            assertEquals(decoded.width, decoded.height)
            assertTrue(decoded.width <= 512)
            val center = decoded.getPixel(decoded.width / 2, decoded.height / 2)
            assertTrue(Color.BLUE == center || Color.blue(center) > Color.red(center) + 150)
        } finally { decoded.recycle() }
    }

    @Test fun unreadableSourceFailsClosed() = runBlocking {
        try {
            prepareClubLogo(PickedImage(byteArrayOf(1, 2, 3), "image/jpeg"), NormalizedCropRect(0f, 0f, 0.5f, 0.5f), 0f)
            fail("Invalid source must never reach upload")
        } catch (e: ClubException) { assertEquals("invalid-logo", e.code) }
    }

    @Test fun invalidRotationCannotFallBackToUncroppedImage() = runBlocking {
        try {
            prepareClubLogo(source(), NormalizedCropRect(0.5f, 0f, 0.5f, 1f), Float.NaN)
            fail("Invalid crop/rotation must fail closed")
        } catch (e: ClubException) { assertEquals("invalid-logo", e.code) }
    }
}
