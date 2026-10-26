package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kungsbackacarcommunity.app.navigation.InMemorySavedPlacesStore
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.PlaceSuggestion
import com.kungsbackacarcommunity.app.navigation.SavePlaceDialog
import com.kungsbackacarcommunity.app.navigation.SavedPlaceKind
import com.kungsbackacarcommunity.app.navigation.SavedPlaces
import com.kungsbackacarcommunity.app.navigation.SavedPlacesScreen

/** S07/S08 production components. Host supplies theme, scale and a visible fixture label.
 * No repositories, network, fabricated street addresses, map imagery or reward data.
 */
@Composable
fun SavedPlacesCataloguePreview(screen: String) {
    val places = remember {
        (listOf(SavedPlaceKind.Home, SavedPlaceKind.Work) + List(4) { SavedPlaceKind.Favourite })
            .mapIndexed { index, kind ->
                SavedPlaces.create(kind, PlaceSuggestion(
                    id = "local-fixture-$index", name = "Lokal testdestination ${index + 1}",
                    address = null, point = LatLng(12.08 + index * 0.001, 57.49),
                ), "Testplats ${index + 1}")
            }
    }
    val store = remember { InMemorySavedPlacesStore(places) }
    when (screen) {
        "saved-places-manage" -> SavedPlacesScreen(store, {}, {}, { _, _ -> })
        "saved-place-edit" -> SavePlaceDialog(
            place = places[2].place, existing = places[2], pendingEdit = null,
            onDismiss = {}, onSave = { _, _ -> }, onRemove = {},
        )
        else -> error("Unknown saved-places catalogue case: $screen")
    }
}
