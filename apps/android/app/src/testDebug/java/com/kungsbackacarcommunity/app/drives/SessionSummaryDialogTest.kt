package com.kungsbackacarcommunity.app.drives

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the end-of-session dialog.
 *
 * Since #853 a finished live session's drive is auto-saved AND auto-kept, so the
 * normal stop path shows NO prompt — the user removes an unwanted drive from
 * History instead. This dialog is now purely the never-lose-a-drive safety net:
 * it renders only on [RecordingState.Failed]. The load-bearing claims here are
 * therefore that the normal (non-failure) states render nothing, and that a save
 * failure never lets the drive vanish silently — it offers Retry, or Close on a
 * refusal no retry can fix.
 */
@RunWith(AndroidJUnit4::class)
class SessionSummaryDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val points =
        listOf(
            RecordedPoint(57.0, 12.0, 1_000L),
            RecordedPoint(57.1, 12.1, 61_000L),
        )

    private fun setDialog(
        state: RecordingState,
        onRetry: () -> Unit = {},
        onDiscard: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                SessionSummaryDialog(
                    state = state,
                    pointsProvider = { points },
                    onRetry = onRetry,
                    onDiscard = onDiscard,
                )
            }
        }
    }

    @Test
    fun savedPendingChoice_showsNoPrompt() {
        // The drive is auto-kept, so the choice state must render nothing at all —
        // no dialog, no Keep/Delete buttons.
        setDialog(state = RecordingState.SavedPendingChoice(elapsedMillis = 60_000L, savePending = true))
        composeTestRule.onNodeWithTag(SESSION_SUMMARY_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun keptPendingSave_showsNoPrompt() {
        setDialog(state = RecordingState.KeptPendingSave(elapsedMillis = 60_000L))
        composeTestRule.onNodeWithTag(SESSION_SUMMARY_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun memberGateRefusal_namesTheMissingMembershipAndOffersCloseInsteadOfRetry() {
        var discarded = 0
        setDialog(
            state =
                RecordingState.Failed(
                    pointCount = 2,
                    elapsedMillis = 60_000L,
                    code = RecordingState.PERMISSION_DENIED,
                ),
            onDiscard = { discarded += 1 },
        )

        // A refusal no retry can fix names the missing membership and offers Close.
        composeTestRule.onNodeWithText(str(R.string.savedDrives_memberRequired)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_retryAction)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_closeButton)).performClick()
        assertEquals(1, discarded)
    }

    @Test
    fun transientFailure_offersRetryAndDoesNotLetTheDriveVanish() {
        var retried = 0
        setDialog(
            state =
                RecordingState.Failed(
                    pointCount = 2,
                    elapsedMillis = 60_000L,
                    code = "UNAVAILABLE",
                ),
            onRetry = { retried += 1 },
        )

        composeTestRule.onNodeWithTag(SESSION_SUMMARY_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_saveError)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_memberRequired)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_retryAction)).performClick()
        assertEquals(1, retried)
    }
}
