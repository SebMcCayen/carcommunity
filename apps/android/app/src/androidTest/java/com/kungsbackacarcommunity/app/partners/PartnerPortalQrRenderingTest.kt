package com.kungsbackacarcommunity.app.partners

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native pixel readback; it does not exercise a physical camera or a remote QR consumer. */
@RunWith(AndroidJUnit4::class)
class PartnerPortalQrRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun displayedQrDecodesToOnlyTheOpaqueTokenWhileTheWindowRemainsSecure() {
        val repo = PartnerPortalScreensTest.FakePortal()
        compose.setContent { KccTheme { PartnerReserveOfferRoute(repo, "offer", {}) } }
        compose.onNodeWithText(text(R.string.partnerPortal_reserve)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.partnerPortal_showQr)).performScrollTo().performClick()
        assertEquals(1, repo.tokenCount)
        // The production bitmap is generated on Dispatchers.Default, which is
        // intentionally outside Compose's UI-idle accounting.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription(text(R.string.partnerPortal_showQr))
                .fetchSemanticsNodes().size == 1
        }
        val qr = compose.onNodeWithContentDescription(text(R.string.partnerPortal_showQr))
            .performScrollTo().assertIsDisplayed()

        compose.runOnIdle { assertSecureWindow() }
        // This is owned, in-process PixelCopy of a synthetic fixture. The test
        // must not clear FLAG_SECURE to make capture or decoding succeed.
        val bitmap = qr.captureToImage().asAndroidBitmap()
        assertEquals("The complete QR must fit as a square, including its quiet zone", bitmap.width, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val decoded = try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        } catch (error: com.google.zxing.NotFoundException) {
            val target = File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "partner-qr-fixture-decode-failure.png",
            )
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            println("Synthetic QR capture ${bitmap.width}x${bitmap.height}; black=${pixels.count { it == android.graphics.Color.BLACK }} white=${pixels.count { it == android.graphics.Color.WHITE }} bounds=${qr.fetchSemanticsNode().boundsInRoot}")
            throw error
        }
        assertEquals("fixture-opaque-token-1234567890", decoded.text)
        assertEquals(1, repo.tokenCount)
        compose.runOnIdle { assertSecureWindow() }
    }

    private fun assertSecureWindow() {
        assertTrue(
            "QR rendering must retain the sensitive-window protection",
            (compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
        )
    }
}
