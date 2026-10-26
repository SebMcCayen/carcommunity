package com.kungsbackacarcommunity.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/** Native Compose on Robolectric; local stores and stub map, no Firebase or network. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedPlacesCatalogueTest {
    @get:Rule val compose = createComposeRule()

    private val home = saved(SavedPlaceKind.Home, "home-address")
    private val work = saved(SavedPlaceKind.Work, "work-address")
    private val favourite = saved(SavedPlaceKind.Favourite, "favourite-address")

    @Test fun filters_onlyChangeVisibleRows_preserveStoreAndOrder() {
        val store = InMemorySavedPlacesStore(listOf(home, work, favourite))
        val before = store.saved()
        compose.setContent { Frame { SavedPlacesScreen(store, {}, {}, { _, _ -> }) } }
        compose.onNodeWithTag(savedPlacesFilterTestTag(null)).assertIsSelected()
        SavedPlaceKind.entries.forEach { kind ->
            compose.onNodeWithTag(savedPlacesFilterTestTag(kind)).performClick().assertIsSelected()
            before.forEach { place ->
                val row = compose.onNodeWithTag(savedPlaceRowTestTag(place.id))
                if (place.kind == kind) row.performScrollTo().assertIsDisplayed() else row.assertDoesNotExist()
            }
        }
        compose.onNodeWithTag(savedPlacesFilterTestTag(null)).performClick()
        before.forEach { compose.onNodeWithTag(savedPlaceRowTestTag(it.id)).assertExists() }
        compose.runOnIdle { assertEquals(before, store.saved()) }
    }

    @Test fun emptyFilter_keepsAddReachable_largeTextAndLightTheme() {
        val store = InMemorySavedPlacesStore(listOf(favourite))
        var adds = 0
        compose.setContent {
            Frame(scale = 2f) { SavedPlacesScreen(store, { adds++ }, {}, { _, _ -> }) }
        }
        SavedPlaceKind.entries.forEach { kind ->
            compose.onNodeWithTag(savedPlacesFilterTestTag(kind)).assertIsDisplayed()
                .performClick().assertIsSelected()
            val bounds = compose.onNodeWithTag(savedPlacesFilterTestTag(kind)).fetchSemanticsNode().boundsInRoot
            assertTrue("Accessible filter target: $bounds", bounds.height >= 48f)
        }
        compose.onNodeWithTag(savedPlacesFilterTestTag(SavedPlaceKind.Work)).performClick()
        compose.onNodeWithTag(SAVED_PLACES_FILTER_EMPTY_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SAVED_PLACES_EMPTY_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SAVED_PLACES_ADD_TEST_TAG).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, adds); assertEquals(listOf(favourite), store.saved()) }
    }

    // Native menu -> AlertDialog window transitions, rename/cancel/share/repoint
    // and confirmed deletion run in SavedPlacesManagementInteractionTest on an
    // emulator. Robolectric loops indefinitely idling that window transition;
    // the native test preserves all assertions instead of disabling the flow.

    @Test fun editor_largeText_kindChangesRetainBoundedName_andOnlyExplicitSaveCallsBack() {
        var result: Pair<SavedPlaceKind, String>? = null
        var dismissed = 0
        compose.setContent {
            Frame(scale = 2f) {
                SavePlaceDialog(favourite.place, null, null, { dismissed++ },
                    { kind, label -> result = kind to label }, {})
            }
        }
        compose.onNodeWithTag(savedPlaceKindTestTag(SavedPlaceKind.Favourite)).assertIsSelected()
        compose.onNodeWithTag(NAV_SAVE_NAME_TEST_TAG).performScrollTo()
            .performTextReplacement("x".repeat(SavedPlaces.MAX_LABEL + 20))
        listOf(SavedPlaceKind.Home, SavedPlaceKind.Work).forEach { kind ->
            compose.onNodeWithTag(savedPlaceKindTestTag(kind)).performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag(NAV_SAVE_NAME_TEST_TAG).assertDoesNotExist()
        }
        compose.onNodeWithTag(savedPlaceKindTestTag(SavedPlaceKind.Favourite)).performScrollTo().performClick()
        compose.onNodeWithTag(NAV_SAVE_NAME_TEST_TAG).performScrollTo()
            .assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, result); assertEquals(0, dismissed) }
        compose.onNodeWithTag(NAV_SAVE_CONFIRM_TEST_TAG).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(SavedPlaceKind.Favourite to "x".repeat(SavedPlaces.MAX_LABEL), result) }
    }

    @Test fun editor_clearName_isOnlyALocalDraftUntilSave() {
        var result: Pair<SavedPlaceKind, String>? = null
        compose.setContent {
            Frame { SavePlaceDialog(favourite.place, favourite, null, {}, { kind, label -> result = kind to label }, {}) }
        }
        compose.onNodeWithTag(NAV_SAVE_NAME_TEST_TAG).performScrollTo()
        compose.onNodeWithContentDescription(str(R.string.savedPlaces_clearName)).performClick()
        compose.runOnIdle { assertEquals(null, result) }
        compose.onNodeWithContentDescription(str(R.string.savedPlaces_clearName)).assertDoesNotExist()
        compose.onNodeWithTag(NAV_SAVE_CONFIRM_TEST_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SavedPlaceKind.Favourite to "", result) }
    }

    @Test fun editor_pendingWorkDefaults_andCancelNeverSavesOrRemoves() {
        var dismissed = 0
        val writes = mutableListOf<String>()
        compose.setContent {
            Frame {
                SavePlaceDialog(favourite.place, favourite, SavedPlaceEdit(SavedPlaceKind.Work, "Retained", "work"),
                    { dismissed++ }, { _, _ -> writes += "save" }, { writes += "remove:$it" })
            }
        }
        compose.onNodeWithTag(savedPlaceKindTestTag(SavedPlaceKind.Work)).assertIsSelected()
        compose.onNodeWithTag(NAV_SAVE_CANCEL_TEST_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, dismissed); assertTrue(writes.isEmpty()) }
    }

    @Test fun editor_existingHome_defaultAndRemoveIdRemainIntact() {
        var removed: String? = null
        compose.setContent {
            Frame { SavePlaceDialog(home.place, home, null, {}, { _, _ -> }, { removed = it }) }
        }
        compose.onNodeWithTag(savedPlaceKindTestTag(SavedPlaceKind.Home)).assertIsSelected()
        compose.onNodeWithTag(NAV_SAVE_NAME_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NAV_SAVE_REMOVE_TEST_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(home.id, removed) }
    }

    @Test fun searchEditor_cancelConsumesRepoint_thenFreshSavePreservesHomeAndPersistsFavourite() {
        val store = InMemorySavedPlacesStore(listOf(home))
        showSearch(store, SavedPlaceEdit(home.kind, home.label, home.id))
        compose.onNodeWithTag(NAV_SAVE_TEST_TAG).performClick()
        compose.onNodeWithTag(savedPlaceKindTestTag(SavedPlaceKind.Home)).assertIsSelected()
        compose.onNodeWithTag(NAV_SAVE_CANCEL_TEST_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(home), store.saved()) }
        compose.onNodeWithTag(NAV_SAVE_TEST_TAG).performClick()
        compose.onNodeWithTag(savedPlaceKindTestTag(SavedPlaceKind.Favourite)).assertIsSelected()
        compose.onNodeWithTag(NAV_SAVE_NAME_TEST_TAG).performScrollTo().performTextReplacement("Explicit save")
        compose.onNodeWithTag(NAV_SAVE_CONFIRM_TEST_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(NAV_SAVE_EDITOR_TEST_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(home, store.saved().first())
            assertEquals("Explicit save", store.saved().last().label)
            assertEquals(SavedPlaceKind.Favourite, store.saved().last().kind)
        }
    }

    @Test fun searchEditor_repointingHome_replacesExistingSingletonOnlyOnSave() {
        val store = InMemorySavedPlacesStore(listOf(home, work, favourite))
        showSearch(store, SavedPlaceEdit(home.kind, home.label, home.id))
        compose.onNodeWithTag(NAV_SAVE_TEST_TAG).performClick()
        compose.runOnIdle { assertEquals(home, store.saved().first()) }
        compose.onNodeWithTag(NAV_SAVE_CONFIRM_TEST_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(3, store.saved().size)
            assertEquals(listOf(home.id, work.id, favourite.id), store.saved().map { it.id })
            assertEquals(LatLng(12.09, 57.50), store.saved().first().place.point)
            assertEquals(listOf(work, favourite), store.saved().drop(1))
        }
    }

    @Test fun searchEditor_changingHomeToWork_repointsWorkAndConsumesOldHome() {
        val store = InMemorySavedPlacesStore(listOf(home, work, favourite))
        showSearch(store, SavedPlaces.editOf(home))
        compose.onNodeWithTag(NAV_SAVE_TEST_TAG).performClick()
        compose.onNodeWithTag(savedPlaceKindTestTag(SavedPlaceKind.Work)).performScrollTo().performClick()
        compose.onNodeWithTag(NAV_SAVE_CONFIRM_TEST_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            // Changing kind during a re-point consumes the original Home, as
            // sweepIdFor has always specified; it must not strand that old row.
            assertEquals(listOf(work.id, favourite.id), store.saved().map { it.id })
            assertEquals(LatLng(12.09, 57.50), store.saved().first().place.point)
            assertEquals(favourite, store.saved().last())
        }
    }

    @Test fun searchEditor_repointingFavourite_sweepsOldIdAndRetainsItsName() {
        val store = InMemorySavedPlacesStore(listOf(home, work, favourite))
        showSearch(store, SavedPlaces.editOf(favourite))
        compose.onNodeWithTag(NAV_SAVE_TEST_TAG).performClick()
        compose.onNodeWithTag(NAV_SAVE_NAME_TEST_TAG).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(NAV_SAVE_CONFIRM_TEST_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(home, work), store.saved().take(2))
            assertEquals(3, store.saved().size)
            assertTrue(store.saved().none { it.id == favourite.id })
            assertEquals(favourite.label, store.saved().last().label)
            assertEquals(LatLng(12.09, 57.50), store.saved().last().place.point)
        }
    }

    private fun showSearch(store: SavedPlacesStore, edit: SavedPlaceEdit) {
        val client = object : MapboxSearchClient {
            override suspend fun geocode(query: String, proximity: LatLng?) = emptyList<PlaceSuggestion>()
            override suspend fun route(origin: LatLng, destination: LatLng): RouteSummary? = null
        }
        val map = StubMapSurface()
        compose.setContent {
            Frame {
                NavigationSearchScreen(map, client, { null }, {}, { _, _ -> }, savedStore = store,
                    initialTarget = LatLng(12.09, 57.50), initialTargetName = "Local test destination",
                    initialSaveEdit = edit)
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag(NAV_SAVE_TEST_TAG))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Composable private fun Frame(scale: Float = 1f, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
            KccTheme(darkTheme = scale == 1f) { Box(Modifier.requiredSize(320.dp, 640.dp)) { content() } }
        }
    }

    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun saved(kind: SavedPlaceKind, id: String) = SavedPlaces.create(kind,
        PlaceSuggestion(id, "Local destination $id", null, LatLng(12.08, 57.49)), "Label $id")
}
