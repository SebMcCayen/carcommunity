package com.kungsbackacarcommunity.app.live

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the live-location control surface (Phase 12 slice 5).
 */
@RunWith(AndroidJUnit4::class)
class LiveLocationScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun activeSession() =
        LiveSessionInfo(
            sessionId = "s1",
            status = LiveSessionStatus.ACTIVE,
            duration = LiveSessionDuration.ONE_HOUR,
            expiresAtMillis = 10_000L,
        )

    @Test
    fun member_notSharing_startsImmediately_withNoDurationPicker() {
        // Starting a Single session is now IMMEDIATE: no time/duration choice is
        // shown, so the duration chips are gone and Start fires the fixed default
        // (SIX_HOURS — the 6h hard cap, which auto-stops with no prolong prompt)
        // window straight through.
        var started: LiveSessionDuration? = null
        composeTestRule.setContent {
            KccTheme {
                LiveLocationScreen(
                    session = null,
                    nowMillis = 0L,
                    actionStatus = LiveActionStatus.Idle,
                    canShare = true,
                    onStart = { started = it },
                    onStop = {},
                    onHideMeNow = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.liveLocation_statusNotSharing)).assertIsDisplayed()
        // The duration chips no longer exist — there is no time to choose.
        composeTestRule.onNodeWithText(str(R.string.liveLocation_duration1h)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.liveLocation_duration2h)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.liveLocation_duration4h)).assertDoesNotExist()
        // Start shares at once for the fixed 6h default window.
        composeTestRule.onNodeWithText(str(R.string.liveLocation_start)).performScrollTo().performClick()
        assertEquals(LiveLocation.DEFAULT_SESSION_DURATION, started)
        assertEquals(LiveSessionDuration.SIX_HOURS, started)
    }

    @Test
    fun member_sharing_showsStopNotStart() {
        var stopped = 0
        composeTestRule.setContent {
            KccTheme {
                LiveLocationScreen(
                    session = activeSession(),
                    nowMillis = 0L,
                    actionStatus = LiveActionStatus.Idle,
                    canShare = true,
                    onStart = {},
                    onStop = { stopped++ },
                    onHideMeNow = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.liveLocation_statusSharing)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.liveLocation_start)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.liveLocation_stop)).performScrollTo().performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun lapsedMember_stillSharing_canStop() {
        // canShare=false (membership/flag lapsed) but a session is active:
        // Stop must still be offered; the membership gate must NOT replace it.
        var stopped = 0
        composeTestRule.setContent {
            KccTheme {
                LiveLocationScreen(
                    session = activeSession(),
                    nowMillis = 0L,
                    actionStatus = LiveActionStatus.Idle,
                    canShare = false,
                    onStart = {},
                    onStop = { stopped++ },
                    onHideMeNow = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.liveLocation_memberRequiredToShare)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.liveLocation_stop)).performScrollTo().performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun nonMember_isGated_butHideMeNowStillWorks() {
        var hidden = 0
        var started: LiveSessionDuration? = null
        composeTestRule.setContent {
            KccTheme {
                LiveLocationScreen(
                    session = null,
                    nowMillis = 0L,
                    actionStatus = LiveActionStatus.Idle,
                    canShare = false,
                    onStart = { started = it },
                    onStop = {},
                    onHideMeNow = { hidden++ },
                )
            }
        }
        // The membership gate is shown and Start is withheld...
        composeTestRule.onNodeWithText(str(R.string.liveLocation_memberRequiredToShare)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.liveLocation_start)).assertDoesNotExist()
        // ...but the privacy stop is always available.
        composeTestRule.onNodeWithText(str(R.string.liveLocation_hideNow)).performScrollTo().performClick()
        assertEquals(1, hidden)
        assertNull(started)
    }
}
