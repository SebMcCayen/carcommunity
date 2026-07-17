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
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the end-of-session save/discard summary.
 *
 * The load-bearing claims here are the irreversible branch's: Discard must ask
 * before it destroys the drive, and cancelling must destroy nothing.
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

    private fun setPrompt(
        state: RecordingState = RecordingState.PromptSave(pointCount = 2, elapsedMillis = 60_000L),
        onSave: () -> Unit = {},
        onDiscard: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                SessionSummaryDialog(
                    state = state,
                    pointsProvider = { points },
                    onSave = onSave,
                    onDiscard = onDiscard,
                )
            }
        }
    }

    @Test
    fun discard_asksForConfirmationBeforeDestroyingTheDrive() {
        var discarded = false
        setPrompt(onDiscard = { discarded = true })

        composeTestRule.onNodeWithText(str(R.string.savedDrives_discardAction)).performClick()

        // The confirmation is up and NOTHING has been discarded yet.
        composeTestRule.onNodeWithTag(DISCARD_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_discardConfirmTitle)).assertIsDisplayed()
        assertFalse("Discard must not fire until confirmed", discarded)
    }

    @Test
    fun discard_confirmed_discardsTheDrive() {
        var discarded = 0
        setPrompt(onDiscard = { discarded += 1 })

        composeTestRule.onNodeWithText(str(R.string.savedDrives_discardAction)).performClick()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_discardConfirmAction)).performClick()

        assertEquals(1, discarded)
    }

    @Test
    fun discard_cancelled_keepsTheSummaryAndDiscardsNothing() {
        var discarded = false
        setPrompt(onDiscard = { discarded = true })

        composeTestRule.onNodeWithText(str(R.string.savedDrives_discardAction)).performClick()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_discardConfirmCancel)).performClick()

        assertFalse("Cancelling the confirmation must not discard", discarded)
        // Back to the forced Save/Discard choice — the drive is still unresolved.
        composeTestRule.onNodeWithTag(SESSION_SUMMARY_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_saveAction)).assertIsDisplayed()
    }

    @Test
    fun save_isNotGuardedByTheConfirmation() {
        var saved = 0
        setPrompt(onSave = { saved += 1 })

        composeTestRule.onNodeWithText(str(R.string.savedDrives_saveAction)).performClick()

        // Saving only adds — it goes straight through.
        assertEquals(1, saved)
    }

    @Test
    fun memberGateRefusal_namesTheMissingMembershipInsteadOfOfferingARetry() {
        setPrompt(
            state =
                RecordingState.Failed(
                    pointCount = 2,
                    elapsedMillis = 60_000L,
                    code = RecordingState.PERMISSION_DENIED,
                ),
        )

        // The v0.8.0 bug surfaced "try again" for a refusal no retry can fix.
        composeTestRule.onNodeWithText(str(R.string.savedDrives_memberRequired)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_saveError)).assertDoesNotExist()
    }

    @Test
    fun transientFailure_offersTheRetryError() {
        setPrompt(
            state =
                RecordingState.Failed(
                    pointCount = 2,
                    elapsedMillis = 60_000L,
                    code = "UNAVAILABLE",
                ),
        )

        composeTestRule.onNodeWithText(str(R.string.savedDrives_saveError)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_memberRequired)).assertDoesNotExist()
    }
}
