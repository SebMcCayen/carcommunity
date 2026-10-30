package com.kungsbackacarcommunity.app.live

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.design.KccTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveMarkerBadgesCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureBothRenderersAndConvoyDirection() {
        val args = InstrumentationRegistry.getArguments()
        val dark = args.getString("liveBadgesTheme") != "light"
        val font = args.getString("liveBadgesFontScale")?.toFloat() ?: 1f
        require(font in 1f..2f)
        compose.setContent { KccTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font)) {
                Surface(Modifier.fillMaxSize()) { LiveMarkerBadgeOverlayFixture(liveMarkerBadgeFixtureAppearance()) }
            }
        } }
        compose.onAllNodesWithTag(LIVE_MEMBERSHIP_RING_TAG).assertCountEquals(3)
        compose.onAllNodesWithTag(LIVE_CLUB_BADGE_TAG).assertCountEquals(3)
        val file = File(compose.activity.getExternalFilesDir(null), "live-marker-badges-${if (dark) "dark" else "light"}-$font.png")
        file.outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
