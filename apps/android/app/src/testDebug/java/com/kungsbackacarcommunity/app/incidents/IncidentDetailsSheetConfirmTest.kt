package com.kungsbackacarcommunity.app.incidents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the on-screen half of the `incidents-confirm` wiring: the "still there?"
 * action on SOMEONE ELSE'S report is now a LIVE, clickable button (it used to be
 * disabled with a "backend not built" note), it disables while a confirmation is
 * in flight, and the shared "confirmed by N" count actually reaches the screen.
 *
 * These are exactly the claims that can be true in the source and false on the
 * screen — an enabled button that fires nothing, or a count computed but never
 * rendered — so they are asserted against the merged semantics tree, not by
 * reading the composable.
 */
@RunWith(AndroidJUnit4::class)
class IncidentDetailsSheetConfirmTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun othersReport(confirmationCount: Int = 0) =
        Incident(
            id = "incident-1",
            type = IncidentType.HAZARD,
            longitude = 12.07,
            latitude = 57.49,
            // Someone else's report → the sheet offers Confirm.
            reporterUid = "someone-else",
            confirmationCount = confirmationCount,
        )

    @Test
    fun confirmActionIsLiveAndInvokesOnConfirm() {
        var confirmed = false
        composeTestRule.setContent {
            KccTheme {
                IncidentDetailsSheet(
                    incident = othersReport(),
                    viewerUid = "viewer",
                    nowMillis = 0L,
                    onConfirm = { confirmed = true },
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNodeWithTag(INCIDENT_DETAILS_CONFIRM_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertTrue("tapping the live confirm button must invoke onConfirm", confirmed)
    }

    @Test
    fun confirmActionIsDisabledWhileAConfirmationIsInFlight() {
        composeTestRule.setContent {
            KccTheme {
                IncidentDetailsSheet(
                    incident = othersReport(),
                    viewerUid = "viewer",
                    nowMillis = 0L,
                    onConfirm = {},
                    onRemove = {},
                    onDismiss = {},
                    confirmInProgress = true,
                )
            }
        }

        composeTestRule
            .onNodeWithTag(INCIDENT_DETAILS_CONFIRM_TAG)
            .assertIsNotEnabled()
    }

    @Test
    fun confirmedByCountShowsWhenOthersHaveConfirmed() {
        composeTestRule.setContent {
            KccTheme {
                IncidentDetailsSheet(
                    incident = othersReport(confirmationCount = 3),
                    viewerUid = "viewer",
                    nowMillis = 0L,
                    onConfirm = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }

        val expected =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getString(R.string.incidents_confirmedBy, 3)
        composeTestRule.onNodeWithTag(INCIDENT_DETAILS_CONFIRM_COUNT_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun confirmedByCountIsHiddenWhenNobodyHasConfirmed() {
        composeTestRule.setContent {
            KccTheme {
                IncidentDetailsSheet(
                    incident = othersReport(confirmationCount = 0),
                    viewerUid = "viewer",
                    nowMillis = 0L,
                    onConfirm = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(INCIDENT_DETAILS_CONFIRM_COUNT_TAG).assertDoesNotExist()
    }
}
