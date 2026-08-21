package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric-backed Compose UI test (fast, blocking `testDebugUnitTest`, no
 * emulator) for [DeployedTrapPopup]'s stateful behaviour — what the pure
 * [PerkMapVisualsTest] cannot cover:
 *  - the localized live remaining-time line renders from the injected clock;
 *  - when the trap EXPIRES, the popup auto-CLOSES (invokes `onDismiss` exactly
 *    once) rather than lingering over an expired trap.
 *
 * The injected `nowProvider` is the clock seam; `mainClock.autoAdvance = false`
 * plus explicit `advanceTimeBy` drives the popup's 1 s countdown `LaunchedEffect`
 * deterministically without the loop hanging the test's idle clock.
 */
@RunWith(AndroidJUnit4::class)
class DeployedTrapPopupTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun trap(expiresAtMillis: Long) =
        OwnTrapMarker(
            trapId = "trap-1",
            latitude = 57.0,
            longitude = 12.0,
            expiresAtMillis = expiresAtMillis,
            deployedAtMillis = 0L,
        )

    @Test
    fun rendersRemainingTime_thenAutoDismissesWhenTheTrapExpires() {
        composeTestRule.mainClock.autoAdvance = false

        // A controllable clock: the popup polls this each tick.
        var fakeNow = 0L
        var dismissCount = 0
        composeTestRule.setContent {
            KccTheme {
                DeployedTrapPopup(
                    // 2 min 30 s of life at now = 0.
                    trap = trap(expiresAtMillis = 150_000L),
                    onDismiss = { dismissCount += 1 },
                    nowProvider = { fakeNow },
                )
            }
        }
        // One frame so the popup lays out + draws.
        composeTestRule.mainClock.advanceTimeByFrame()

        // The live remaining-time line renders in the current locale ("2 min 30 s
        // kvar" / "2m 30s left"), asserted via the resource so it is locale-agnostic.
        composeTestRule
            .onNodeWithText(str(R.string.crownHunt_perkDetailRemainingMs, 2, 30))
            .assertIsDisplayed()
        assertEquals("popup dismissed before expiry", 0, dismissCount)

        // Push the clock PAST expiry, then wake the 1 s countdown loop: it re-reads
        // nowProvider, sees the trap has expired, and closes the popup.
        fakeNow = 200_000L
        composeTestRule.mainClock.advanceTimeBy(1_000L)

        assertEquals("expiry closes the popup exactly once", 1, dismissCount)
    }
}
