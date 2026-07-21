package com.kungsbackacarcommunity.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R

/**
 * Localized display name for a saved place, shared by every saved-places surface
 * so a place reads identically in the search bar's saved card
 * ([NavigationSearchScreen]) and the Saved-places management screen
 * ([SavedPlacesScreen]) — and the rule can't drift between two copies.
 *
 * The singletons always read "Home"/"Work" from resources: their stored label is
 * the raw street name of wherever they were first saved and is never shown there.
 * Favourites read the user's own label.
 *
 * Lives in its own file (not the pure [SavedPlaces], which is deliberately
 * Android-free) because it needs [stringResource]; kept `internal` so both
 * screens in this package call the one definition.
 */
@Composable
internal fun SavedPlace.displayLabel(): String =
    when (kind) {
        SavedPlaceKind.Home -> stringResource(R.string.addressSearch_savedHome)
        SavedPlaceKind.Work -> stringResource(R.string.addressSearch_savedWork)
        SavedPlaceKind.Favourite -> label
    }
