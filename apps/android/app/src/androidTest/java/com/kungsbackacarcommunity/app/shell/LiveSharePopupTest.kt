package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The [LiveSharePopup] sheet, rendered directly (a running live session cannot
 * be reached from the no-Firebase shell): the stop-only sheet while sharing, the
 * manage sheet's privacy controls without a stop handler, and the accurate
 * unavailable notice when the LIVE_LOCATION flag is off. Part of the map-first
 * shell suite split out of `MapFirstShellTest` (see [MapFirstShellTestSupport]).
 */
@RunWith(AndroidJUnit4::class)
class LiveSharePopupTest : MapFirstShellTestSupport() {

    /**
     * The live-share STOP sheet — the [LiveSharePopup] the bottom bar's live
     * control raises while a session runs — is a stop sheet and nothing more:
     * "Hide me now" and "More options" were removed from it, so the only thing
     * pressing the stop sign can lead to is stopping. Rendered directly here
     * because a running session cannot be reached from the no-Firebase shell.
     */
    @Test
    fun liveStopSheet_whileSharing_exposesStopAndNothingElse() {
        var stopped = 0
        composeTestRule.setContent {
            KccTheme {
                LiveSharePopup(
                    isSharing = true,
                    canShareLive = true,
                    onStart = {},
                    onStop = { stopped += 1 },
                    onHideMeNow = {},
                    onOpenDetails = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithTag(MAP_HOME_LIVE_POPUP_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.liveLocation_stop)).assertIsDisplayed()
        // The two rows Seb asked to be removed are gone.
        composeTestRule.onNodeWithText(str(R.string.liveLocation_hideNow)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.shell_liveDetails)).assertDoesNotExist()
        // While sharing there is no Start row.
        composeTestRule.onNodeWithText(str(R.string.liveLocation_start)).assertDoesNotExist()
        // The prominent Stop action drives the wired handler.
        composeTestRule.onNodeWithText(str(R.string.liveLocation_stop)).performClick()
        composeTestRule.runOnIdle { assertEquals(1, stopped) }
    }

    /**
     * The SAME sheet without a stop handler (turn-by-turn navigation's reuse)
     * shows no Stop row, but must keep the two privacy controls reachable —
     * removing them from the STOP sheet must not remove them from the app.
     */
    @Test
    fun liveManageSheet_withoutStopHandler_keepsHideAndAudience() {
        composeTestRule.setContent {
            KccTheme {
                LiveSharePopup(
                    isSharing = true,
                    canShareLive = true,
                    onStart = {},
                    onHideMeNow = {},
                    onOpenDetails = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.liveLocation_stop)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.liveLocation_hideNow)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.shell_liveDetails)).assertIsDisplayed()
    }

    /**
     * Idle + LIVE_LOCATION flag OFF (canShareLive = false). Starting is
     * FLAG-gated, not member-gated, so the sheet must show the "not available"
     * notice — NOT the old message claiming an active membership is required —
     * while still offering the audience ("More options") entry. Guards Copilot's
     * misleading-message finding and proves the row is driven by the model.
     */
    @Test
    fun liveManageSheet_whenFlagOff_showsUnavailableNoticeNotMemberMessage() {
        composeTestRule.setContent {
            KccTheme {
                LiveSharePopup(
                    isSharing = false,
                    canShareLive = false,
                    onStart = {},
                    onHideMeNow = {},
                    onOpenDetails = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithTag(MAP_HOME_LIVE_POPUP_TAG).assertIsDisplayed()
        // The accurate feature-unavailable notice is shown...
        composeTestRule
            .onNodeWithText(str(R.string.liveLocation_shareUnavailable))
            .assertIsDisplayed()
        // ...and the misleading membership message is NOT.
        composeTestRule
            .onNodeWithText(str(R.string.liveLocation_memberRequiredToShare))
            .assertDoesNotExist()
        // No Start row while the flag is off; audience entry stays reachable.
        composeTestRule.onNodeWithText(str(R.string.liveLocation_start)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.shell_liveDetails)).assertIsDisplayed()
    }
}
