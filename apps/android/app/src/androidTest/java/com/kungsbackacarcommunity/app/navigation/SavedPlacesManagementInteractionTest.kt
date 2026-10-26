package com.kungsbackacarcommunity.app.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-window menu/dialog transitions. Local device store only, no account/network. */
@RunWith(AndroidJUnit4::class)
class SavedPlacesManagementInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun renameCancelSaveShareRepointAndConfirmedDeletionKeepTheirExistingSemantics() {
        val place = SavedPlaces.create(SavedPlaceKind.Favourite,
            PlaceSuggestion("local-qa", "Local QA destination", null, LatLng(57.49, 12.08)), "Original")
        val home = SavedPlaces.create(SavedPlaceKind.Home, place.place.copy(id = "qa-home"), "Home")
        val work = SavedPlaces.create(SavedPlaceKind.Work, place.place.copy(id = "qa-work"), "Work")
        val store = InMemorySavedPlacesStore(listOf(home, work, place))
        val shared = mutableListOf<Pair<String, LatLng>>()
        val changed = mutableListOf<SavedPlace>()
        rule.setContent {
            KccTheme { SavedPlacesScreen(store, {}, { changed += it }, { name, point -> shared += name to point }) }
        }

        fun openMenu(label: String, target: SavedPlace = place) {
            rule.onNodeWithTag(savedPlaceRowTestTag(target.id)).performScrollTo()
            rule.onNodeWithContentDescription(str(R.string.savedPlaces_moreActions, label)).performClick()
        }
        openMenu("Original")
        rule.onNodeWithText(str(R.string.savedPlaces_rename)).performClick()
        rule.onNodeWithText(str(R.string.savedPlaces_renameLabel)).performTextReplacement("Cancelled")
        rule.onNodeWithText(str(R.string.savedPlaces_cancel)).performClick()
        rule.runOnIdle { assertEquals(listOf(home, work, place), store.saved()) }

        openMenu("Original")
        rule.onNodeWithText(str(R.string.savedPlaces_rename)).performClick()
        rule.onNodeWithText(str(R.string.savedPlaces_renameLabel)).performTextReplacement("Renamed")
        rule.onNodeWithText(str(R.string.savedPlaces_renameSave)).performClick()
        val renamed = place.copy(label = "Renamed")
        rule.runOnIdle { assertEquals(listOf(home, work, renamed), store.saved()) }

        openMenu("Renamed")
        rule.onNodeWithText(str(R.string.savedPlaces_share)).performClick()
        openMenu(str(R.string.addressSearch_savedHome), home)
        rule.onNodeWithText(str(R.string.savedPlaces_rename)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.savedPlaces_changeAddress)).performClick()

        openMenu(str(R.string.addressSearch_savedWork), work)
        rule.onNodeWithText(str(R.string.savedPlaces_delete)).performClick()
        rule.onNode(hasText(str(R.string.savedPlaces_cancel)) and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle { assertEquals(3, store.saved().size) }
        openMenu(str(R.string.addressSearch_savedWork), work)
        rule.onNodeWithText(str(R.string.savedPlaces_delete)).performClick()
        rule.onNode(hasText(str(R.string.savedPlaces_deleteConfirm)) and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle {
            assertEquals(listOf(home, renamed), store.saved())
            assertEquals(listOf("Renamed" to place.place.point), shared)
            assertEquals(listOf(home), changed)
        }
    }

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)
}
