package com.kungsbackacarcommunity.app.incidents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the claim that made a generic `incidents.detailsTitle` string
 * ("Reported incident") unnecessary and let it be deleted from the contract:
 * the sheet titles itself with the incident's OWN CATEGORY, which is strictly
 * more informative than a fixed heading would be.
 *
 * This asserts the label actually reaches a node in the merged semantics tree —
 * not merely that the string resource exists — because "the sheet already has a
 * title" is exactly the kind of claim that can be true in the source and false
 * on screen (a title slot that is rendered but never populated reads as an
 * untitled dialog to a screen-reader user).
 *
 * The category badge beside the title is deliberately NOT asserted here: it is
 * `contentDescription = null` on purpose, so the category is announced once
 * rather than twice.
 */
@RunWith(AndroidJUnit4::class)
class IncidentDetailsSheetTitleTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun incident(type: IncidentType) =
        Incident(
            id = "incident-1",
            type = type,
            longitude = 12.07,
            latitude = 57.49,
            reporterUid = "someone-else",
        )

    private fun show(type: IncidentType) {
        composeTestRule.setContent {
            KccTheme {
                IncidentDetailsSheet(
                    incident = incident(type),
                    viewerUid = "viewer",
                    nowMillis = 0L,
                    onConfirm = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun titlesItselfWithTheIncidentsOwnCategory() {
        show(IncidentType.ACCIDENT)

        composeTestRule
            .onNodeWithText(str(R.string.incidents_typeAccident))
            .assertIsDisplayed()
    }

    @Test
    fun aDifferentCategoryGivesADifferentTitle() {
        show(IncidentType.ROADWORK)

        composeTestRule
            .onNodeWithText(str(R.string.incidents_typeRoadwork))
            .assertIsDisplayed()
        // The point of the per-category title: it is not a fixed heading that
        // would read the same for every incident on the map.
        composeTestRule
            .onNodeWithText(str(R.string.incidents_typeAccident))
            .assertDoesNotExist()
    }
}
