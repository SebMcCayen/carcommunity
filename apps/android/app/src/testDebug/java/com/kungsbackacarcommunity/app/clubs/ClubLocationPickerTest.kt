package com.kungsbackacarcommunity.app.clubs

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Parent runs the resource-aware Robolectric suite centrally; no emulator needed. */
@RunWith(AndroidJUnit4::class)
class ClubLocationPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val busy = mutableStateOf(false)
    private val formError = mutableStateOf<Throwable?>(null)
    private val saved = mutableListOf<Map<String, Any?>>()
    private var dismissed = 0
    private fun text(id: Int) = compose.activity.getString(id)
    private val catalog by lazy {
        compose.activity.assets.open(CLUB_LOCATION_ASSET).bufferedReader(Charsets.UTF_8).use {
            ClubLocationCatalog.parse(it.readText())
        }
    }

    private fun show(club: Club? = null, loader: (suspend () -> ClubLocationCatalog)? = null) {
        compose.setContent {
            KccTheme {
                ClubEditor(club, busy.value, onDismiss = { dismissed++ }, error = formError.value, catalogLoader = loader) {
                    saved += it
                    busy.value = true
                }
            }
        }
    }

    private fun waitForCatalog() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("club-county-field").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun fillPresentation() {
        compose.onNode(hasText(text(R.string.clubs_name)) and hasSetTextAction()).performScrollTo().performTextInput("Test club")
        compose.onNode(hasText(text(R.string.clubs_description)) and hasSetTextAction()).performScrollTo().performTextInput("A description of our club")
    }

    private fun pickCounty(code: String) {
        compose.onNodeWithTag("club-county-field").performScrollTo().performClick()
        compose.onNodeWithTag("club-county-list").performScrollToNode(hasTestTag("club-county-$code"))
        compose.onNodeWithTag("club-county-$code").performClick()
    }

    private fun pickLocality(query: String, code: String) {
        compose.onNodeWithTag("club-locality-field").performScrollTo().performClick()
        compose.onNodeWithTag("club-locality-search").performTextInput(query)
        compose.onNodeWithTag("club-locality-list").performScrollToNode(hasTestTag("club-locality-$code"))
        compose.onNodeWithTag("club-locality-$code").performClick()
    }

    @Test fun packagedCatalogRequiresCountyThenLocalityAndChangingCountyClearsChoice() {
        show()
        waitForCatalog()
        fillPresentation()
        // New creation has no location text input, even before a county is chosen.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithTag("club-locality-field").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.clubs_save)).assertIsNotEnabled()
        pickCounty("13")
        compose.onNodeWithTag("club-locality-field").assertIsEnabled()
        compose.onNodeWithText(text(R.string.clubs_save)).assertIsNotEnabled()
        pickLocality("asa", "1384TB126")
        compose.onNodeWithTag("club-locality-field").assertTextContains("Åsa")
        compose.onNodeWithText(text(R.string.clubs_save)).assertIsEnabled()
        pickCounty("14")
        compose.onNodeWithTag("club-locality-field").assertTextContains(text(R.string.clubs_chooseLocality))
        compose.onNodeWithText(text(R.string.clubs_save)).assertIsNotEnabled()
        pickLocality("Göteborg", "1480TC108")
        compose.onNodeWithText(text(R.string.clubs_save)).performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals("Göteborg, Västra Götalands län", saved.single()["location"])
            assertEquals(setOf("name", "description", "location", "visibility"), saved.single().keys)
        }
    }

    @Test fun KungsbackaIsSelectableAndSavingAndFailureKeepTheSelectedPair() {
        show()
        waitForCatalog()
        fillPresentation()
        pickCounty("13")
        pickLocality("Kungsbacka", "1384TC117")
        compose.onNodeWithTag("club-locality-field").assertTextContains("Kungsbacka")
        compose.onNodeWithTag("club-locality-field").assertTextContains(compose.activity.getString(R.string.clubs_localityPartOf, "Göteborg"))
        compose.onNodeWithText(text(R.string.clubs_save)).performClick()
        compose.onNodeWithTag("club-county-field").assertIsNotEnabled()
        compose.onNodeWithTag("club-locality-field").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.clubs_save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.clubs_cancel)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("Kungsbacka, Hallands län", saved.single()["location"])
            busy.value = false
            formError.value = ClubException("permission-denied")
        }
        compose.onNodeWithText(text(R.string.clubs_permissionError)).assertExists()
        compose.onNodeWithTag("club-locality-field").assertTextContains("Kungsbacka")
        compose.onNodeWithText(text(R.string.clubs_save)).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test fun emptySearchHasNoFreeTextFallbackOrAutomaticSelection() {
        show()
        waitForCatalog()
        fillPresentation()
        pickCounty("13")
        compose.onNodeWithTag("club-locality-field").performScrollTo().performClick()
        compose.onNodeWithTag("club-locality-search").performTextInput("no such locality 123")
        compose.onNodeWithText(text(R.string.addressSearch_noResults)).assertExists()
        compose.onAllNodesWithText(text(R.string.clubs_cancel)).onLast().performClick()
        compose.onNodeWithText(text(R.string.clubs_save)).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
    }

    @Test fun catalogFailureBlocksCreationAndCanRecoverWithoutDiscardingTheForm() {
        val loaded = catalog
        val attempts = AtomicInteger()
        val loader: suspend () -> ClubLocationCatalog = {
            if (attempts.getAndIncrement() == 0) error("Cannot read bundled asset")
            loaded
        }
        show(loader = loader)
        fillPresentation()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(R.string.clubs_locationCatalogError)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.clubs_save)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithText(text(R.string.clubs_retry)).performScrollTo().performClick()
        waitForCatalog()
        pickCounty("13")
        pickLocality("Åsa", "1384TB126")
        compose.onNodeWithText(text(R.string.clubs_save)).performClick()
        compose.runOnIdle { assertEquals("Test club", saved.single()["name"]); assertEquals(2, attempts.get()) }
    }

    @Test fun legacyLocationRemainsEditableAndUnrelatedUpdatesDoNotGuessACounty() {
        val legacy = Club("legacy", "Old club", "Existing description", "Göteborg / Kungsbacka",
            "open", "owner", null, 1, 0, ClubStats())
        show(legacy)
        compose.onAllNodesWithTag("club-county-field").assertCountEquals(0)
        compose.onNode(hasText(text(R.string.clubs_name)) and hasSetTextAction()).performTextReplacement("Renamed club")
        compose.onNodeWithText(text(R.string.clubs_save)).performClick()
        compose.runOnIdle { assertEquals(legacy.location, saved.single()["location"]) }
    }
}
