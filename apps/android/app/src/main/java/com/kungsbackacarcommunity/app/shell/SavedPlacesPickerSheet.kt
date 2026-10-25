package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.navigation.SavedPlace
import com.kungsbackacarcommunity.app.navigation.displayLabel
import com.kungsbackacarcommunity.app.navigation.icon

/** Test tags for the saved-places picker sheet. */
const val SAVED_PLACES_PICKER_SHEET_TEST_TAG = "saved_places_picker_sheet"
const val SAVED_PLACES_PICKER_EMPTY_TEST_TAG = "saved_places_picker_empty"

/** Test-tag prefix for one saved-place row (suffixed with the place id). */
fun savedPlacePickerRowTestTag(id: String): String = "saved_places_picker_row_$id"

/**
 * The saved-locations picker the map's pin control opens: a compact list of the
 * member's saved places (the SAME [places] the address-search bar reads, off the
 * shared store), each a tap target that jumps the map to it.
 *
 * Reuses the shared saved-place display helpers ([displayLabel] / [icon]) rather
 * than a parallel presentation, so a place reads here exactly as it does in the
 * search bar and the Saved-places screen. The full management surface
 * ([com.kungsbackacarcommunity.app.navigation.SavedPlacesScreen]) is deliberately
 * NOT reused wholesale: it is a rename/delete/change-address editor with no
 * tap-to-navigate affordance, which is the opposite of what this picker needs.
 *
 * @param places the saved places, already ordered by the store.
 * @param onSelect move the map to this place (reuses the same in-app "move map to
 *   point" flow a chat geo-link tap uses) and dismiss.
 */
@Composable
fun SavedPlacesPickerSheet(
    places: List<SavedPlace>,
    onSelect: (SavedPlace) -> Unit,
    onDismiss: () -> Unit,
) {
    MapDetailSheet(
        title = stringResource(R.string.shell_savedPlacesPickerTitle),
        onDismiss = onDismiss,
        modifier = Modifier.testTag(SAVED_PLACES_PICKER_SHEET_TEST_TAG),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = KccSpacing.s2),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            if (places.isEmpty()) {
                Text(
                    text = stringResource(R.string.shell_savedPlacesPickerEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s3)
                            .testTag(SAVED_PLACES_PICKER_EMPTY_TEST_TAG),
                )
            } else {
                places.forEach { place ->
                    SavedPlaceRow(place = place, onClick = { onSelect(place) })
                }
            }
        }
    }
}

@Composable
private fun SavedPlaceRow(place: SavedPlace, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(savedPlacePickerRowTestTag(place.id)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = place.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                Text(text = place.displayLabel(), style = MaterialTheme.typography.titleMedium)
                val address = place.place.address ?: place.place.name
                if (address != place.displayLabel()) {
                    Text(text = address, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
