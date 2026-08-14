package com.kungsbackacarcommunity.app.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric JVM Compose UI test (no emulator) covering the issue #850 fix:
 * the "Report a problem" form must clear its `rememberSaveable` text once a
 * submission SUCCEEDS, so a form restored after process death is empty rather
 * than pre-filled with the already-sent report. A FAILED submit must NOT clear
 * the text so the user can retry without retyping.
 *
 * Lives in `src/testDebug` for the same reason as OnboardingScreenTest:
 * `createComposeRule()` needs the ComponentActivity host from the debug-only
 * `ui-test-manifest`.
 */
@RunWith(AndroidJUnit4::class)
class FeedbackReportScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = FeedbackClientContext("1.2.3", "Android 14", "Pixel 8")

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun successfulSubmitClearsTheForm_andStaysClearedAcrossRestore() {
        val restorationTester = StateRestorationTester(composeTestRule)
        var status by mutableStateOf<FeedbackStatus>(FeedbackStatus.Idle)

        restorationTester.setContent {
            KccTheme {
                FeedbackReportScreen(
                    status = status,
                    clientContext = context,
                    onSubmit = {},
                    onViewIssue = {},
                    onBack = {},
                )
            }
        }

        val descriptionLabel = str(R.string.feedback_descriptionLabel)
        // Type a report, then land a successful submit.
        composeTestRule.onNodeWithText(descriptionLabel).performScrollTo().performTextInput("Map crashed")
        composeTestRule.onNodeWithText("Map crashed").assertIsDisplayed()

        status = FeedbackStatus.Done(issueUrl = null)
        composeTestRule.waitForIdle()
        // Confirmation is shown; the form text node is gone.
        composeTestRule.onNodeWithText(str(R.string.feedback_success)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Map crashed").assertDoesNotExist()

        // Simulate the OS killing + recreating the process: rememberSaveable is
        // restored from its saved bundle. The Done confirmation (in-memory only)
        // is lost, so the status resets to Idle and the form re-shows.
        restorationTester.emulateSavedInstanceStateRestore()
        status = FeedbackStatus.Idle
        composeTestRule.waitForIdle()

        // The bug (#850) would show "Map crashed" here; the fix leaves it empty.
        composeTestRule.onNodeWithText("Map crashed").assertDoesNotExist()
        composeTestRule.onNodeWithText(descriptionLabel).assertIsDisplayed()
    }

    @Test
    fun failedSubmitKeepsTheTypedText() {
        var status by mutableStateOf<FeedbackStatus>(FeedbackStatus.Idle)

        composeTestRule.setContent {
            KccTheme {
                FeedbackReportScreen(
                    status = status,
                    clientContext = context,
                    onSubmit = {},
                    onViewIssue = {},
                    onBack = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(str(R.string.feedback_descriptionLabel))
            .performScrollTo()
            .performTextInput("Map crashed")

        status = FeedbackStatus.Failed(FeedbackFailureReason.UNKNOWN)
        composeTestRule.waitForIdle()

        // A failure keeps the form filled so the user can retry without retyping.
        composeTestRule.onNodeWithText("Map crashed").assertIsDisplayed()
    }
}
