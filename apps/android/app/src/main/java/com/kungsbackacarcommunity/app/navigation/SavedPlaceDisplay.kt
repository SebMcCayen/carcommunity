package com.kungsbackacarcommunity.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * Icon for a saved place's kind, shared by every saved-places surface (the search
 * bar's saved card in [NavigationSearchScreen], the save dialog's kind chips, and
 * the Saved-places management screen [SavedPlacesScreen]) so the same kind never
 * reads with a different glyph on one screen than another.
 *
 * Not `@Composable` (a plain icon lookup, no composition state) and `internal` so
 * both screens in this package call the one definition.
 */
internal fun SavedPlaceKind.icon(): ImageVector =
    when (this) {
        SavedPlaceKind.Home -> Icons.Filled.Home
        SavedPlaceKind.Work -> Icons.Filled.Work
        SavedPlaceKind.Favourite -> Icons.Filled.Star
    }
