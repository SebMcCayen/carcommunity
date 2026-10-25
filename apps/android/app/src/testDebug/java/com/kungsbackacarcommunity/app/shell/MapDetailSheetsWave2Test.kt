package com.kungsbackacarcommunity.app.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.incidents.ClearVoteEligibility
import com.kungsbackacarcommunity.app.incidents.INCIDENT_DETAILS_CLEAR_TAG
import com.kungsbackacarcommunity.app.incidents.INCIDENT_DETAILS_CONFIRM_TAG
import com.kungsbackacarcommunity.app.incidents.Incident
import com.kungsbackacarcommunity.app.incidents.IncidentDetailsSheet
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.navigation.SavedPlaces
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI regressions for the map-owned sheets; no screenshot host or external services. */
@RunWith(AndroidJUnit4::class)
class MapDetailSheetsWave2Test {
    @get:Rule val compose = createComposeRule()

    @Test
    fun placeActions_keepTheirOriginalCallbacks() {
        val actions = mutableListOf<String>()
        compose.setContent {
            KccTheme {
                PlaceActionsSheet(placeName = null, coordinateText = "57.49, 12.08",
                    onNavigate = { actions += "navigate" }, onCopy = { actions += "copy" },
                    onSave = { actions += "save" }, onDismiss = {})
            }
        }
        listOf(PLACE_ACTIONS_NAVIGATE_TEST_TAG, PLACE_ACTIONS_COPY_TEST_TAG, PLACE_ACTIONS_SAVE_TEST_TAG).forEach {
            compose.onNodeWithTag(it).performScrollTo().performClick()
        }
        assertEquals(listOf("navigate", "copy", "save"), actions)
    }

    @Test
    fun saveLocation_keepsNameLimitAfterMovingToSheet() {
        var saved: String? = null
        compose.setContent {
            KccTheme {
                SaveLocationDialog(initialName = "", coordinateHint = "57.49, 12.08",
                    onSave = { saved = it }, onDismiss = {})
            }
        }
        compose.onNodeWithTag(SAVE_LOCATION_NAME_TEST_TAG).performScrollTo()
            .performTextReplacement("a".repeat(SavedPlaces.MAX_LABEL + 12))
        compose.onNodeWithTag(SAVE_LOCATION_SAVE_TEST_TAG).performScrollTo().performClick()
        assertEquals("a".repeat(SavedPlaces.MAX_LABEL), saved)
    }

    @Test
    fun incidentLongNote_largeText_keepsConfirmReachableAndClearBlocked() {
        var confirmed = 0
        compose.setContent {
            KccTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    IncidentDetailsSheet(
                        incident = Incident(id = "local-test", type = IncidentType.ROADWORK,
                            latitude = 57.49, longitude = 12.08, reporterUid = "other-test-user",
                            note = "Roadwork. ".repeat(40)),
                        viewerUid = "test-viewer", nowMillis = 0L,
                        onConfirm = { confirmed++ }, onRemove = {}, onDismiss = {},
                        onReportCleared = {}, clearEligibility = ClearVoteEligibility.TooFar,
                    )
                }
            }
        }
        compose.onNodeWithTag(INCIDENT_DETAILS_CONFIRM_TAG).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(INCIDENT_DETAILS_CLEAR_TAG).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        assertEquals(1, confirmed)
    }
}
