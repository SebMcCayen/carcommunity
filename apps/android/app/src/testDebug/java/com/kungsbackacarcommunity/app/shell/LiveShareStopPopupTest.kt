package com.kungsbackacarcommunity.app.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveShareStopPopupTest {
    @get:Rule val compose = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun stopExplainsHistoryAndClosesBeforeTheBackgroundCommandCompletes() {
        val open = mutableStateOf(true)
        val gate = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        var finished = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            KccTheme {
                if (open.value) {
                    LiveSharePopup(
                        isSharing = true,
                        canShareLive = true,
                        onStart = {},
                        onHideMeNow = {},
                        onOpenDetails = {},
                        onDismiss = { actions += "dismiss"; open.value = false },
                        onStop = {
                            actions += "stop"
                            scope.launch { gate.await(); finished = true }
                        },
                    )
                }
            }
        }
        compose.onNodeWithText(str(R.string.liveLocation_stopSavesToHistory)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.liveLocation_stop)).performClick()
        compose.onNodeWithTag(MAP_HOME_LIVE_POPUP_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("dismiss", "stop"), actions)
            assertFalse(finished)
            gate.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(finished) }
        compose.onNodeWithTag(MAP_HOME_LIVE_POPUP_TAG).assertDoesNotExist()
    }

    @Test
    fun navigationPrivacySheetDoesNotClaimToSaveAStoppedDrive() {
        compose.setContent {
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
        compose.onNodeWithText(str(R.string.liveLocation_stopSavesToHistory)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.liveLocation_hideNow)).assertIsDisplayed()
    }
}
