package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.MapCameraSnapshot
import com.kungsbackacarcommunity.app.shell.MapScreenPoint
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric-backed Compose UI test (fast, blocking `testDebugUnitTest`, no
 * emulator) for the placer-only spike-strip TAP flow — the headline of this PR.
 *
 * The pure timing helpers are covered by [PerkMapVisualsTest]; this covers what
 * they cannot: that a live own trap actually renders a per-trap tap target, that
 * the target carries the localized "show details" content description (so TalkBack
 * announces it), and that tapping it reports the SPECIFIC trap's id back to the
 * host — the id the host resolves into [DeployedTrapPopup].
 *
 * [StubMapSurface] stands in for the renderer: a fixed camera + a fixed on-screen
 * projection, so the overlay draws + places its target without a Mapbox GL surface.
 * `mainClock.autoAdvance = false` keeps the glyph's infinite pulse from holding the
 * test's idle clock busy (the overlay composes the animated child once a live,
 * on-screen trap exists), while composition/layout still settle for the assertions.
 */
@RunWith(AndroidJUnit4::class)
class SpikeStripOverlayTapTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun surfaceProjectingTo(x: Float, y: Float): StubMapSurface {
        val surface = StubMapSurface(autoLoad = false)
        // A settled camera so the overlay has something to reproject against, and a
        // fixed trustworthy on-screen pixel so the single trap lands inside the
        // viewport (well within any default Robolectric window).
        surface.setCameraSnapshotForTest(MapCameraSnapshot.of(57.0, 12.0, 14.0, 0.0, 0.0))
        surface.setProjectionForTest { _, _ -> MapScreenPoint(x = x, y = y, trustworthy = true) }
        return surface
    }

    private fun liveTrap(id: String) =
        OwnTrapMarker(
            trapId = id,
            latitude = 57.0,
            longitude = 12.0,
            // Live at the fixed `now` below (10 s ahead), 6 s span.
            expiresAtMillis = 10_000L,
            deployedAtMillis = 4_000L,
        )

    @Test
    fun liveTrapRendersALabelledTapTarget_andTapReportsItsId() {
        // The infinite pulse would otherwise keep the idle clock busy forever.
        composeTestRule.mainClock.autoAdvance = false

        var tapped: String? = null
        composeTestRule.setContent {
            SpikeStripOverlay(
                mapSurface = surfaceProjectingTo(x = 120f, y = 120f),
                traps = listOf(liveTrap("trap-1")),
                onTrapTap = { tapped = it.trapId },
                // Pin "now" so the trap is unambiguously live and the pure ticker does
                // not depend on wall-clock time in the test.
                nowProvider = { 1_000L },
            )
        }
        // Advance ONE frame so layout + draw finalize the target's on-screen bounds
        // (the clock is paused, so this does not let the infinite pulse run on).
        composeTestRule.mainClock.advanceTimeByFrame()

        // The per-trap target exists and exposes the localized "show details" label.
        composeTestRule
            .onNodeWithContentDescription(str(R.string.crownHunt_perkTrapMapTapLabel))
            .assertIsDisplayed()

        // Tapping the specific trap's target reports THAT trap's id to the host.
        composeTestRule.onNodeWithTag(SPIKE_STRIP_TAP_TAG + "trap-1").performClick()
        assertEquals("trap-1", tapped)
    }
}
